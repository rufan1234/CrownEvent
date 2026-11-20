package com.smp.crown;

import com.destroystokyo.paper.ParticleBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Holds all event state and runs every scheduled task.
 *
 * Threading note: the Bukkit API (particles, inventories, blocks) is NOT thread-safe, so every task here runs on the
 * main thread. The particle task is still lag-free: it only touches players currently wearing the Crown (normally 0-1),
 * spawns ~20 packet-only particles per run and does nothing at all when nobody wears it. The only genuinely heavy
 * work (finding a safe spot) uses Paper's asynchronous chunk loading.
 */
public final class EventManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 0.9f);
    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE, Material.LAVA);

    private final CrownPlugin plugin;
    private final CrownItem crown;
    private final File dataFile;

    private final List<Town> towns = new ArrayList<>();
    private final Set<UUID> wearers = new HashSet<>();
    private final Set<String> blockedCommands = new HashSet<>();
    private final Set<TeleportCause> blockedCauses = EnumSet.noneOf(TeleportCause.class);

    // ---- persisted state ----
    private boolean eventActive;     // global restriction window
    private boolean huntActive;      // crown still has to be delivered
    private boolean chestLooted;
    private long endsAt;
    private long startedAt;
    private long lastCrownCreated;
    private String lastTrigger = "";
    private Location chestLocation;

    // ---- runtime state ----
    private UUID carrier;
    private boolean spawning;
    private int tick;
    private long lastExpiredMsg;

    public EventManager(CrownPlugin plugin, CrownItem crown) {
        this.plugin = plugin;
        this.crown = crown;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        loadData();
        reloadCaches();
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        var sched = Bukkit.getScheduler();
        sched.runTaskTimer(plugin, this::scheduleTick, 100L, 200L);   // every 10 s
        sched.runTaskTimer(plugin, this::watchCarrier, 40L, 10L);     // every 0.5 s
        sched.runTaskTimer(plugin, this::visualTick, 40L, 4L);        // crown particles / potion effects
        long interval = Math.max(5L, plugin.getConfig().getLong("crown.expiry-check-seconds", 30L)) * 20L;
        sched.runTaskTimer(plugin, this::expiryScan, 200L, interval);
        if (lastCrownCreated > 0) {
            scheduleExactScan(lastCrownCreated + crown.lifespanMillis());
        }
    }

    public void shutdown() {
        Bukkit.getScheduler().cancelTasks(plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (wearers.contains(p.getUniqueId())) {
                removeEffects(p);
            }
        }
        wearers.clear();
        saveData();
    }

    public void reloadCaches() {
        FileConfiguration cfg = plugin.getConfig();

        towns.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("towns");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(key);
                if (t == null) {
                    continue;
                }
                towns.add(new Town(key, t.getString("name", key), t.getString("world", "world"),
                        t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getDouble("radius", 50.0)));
            }
        }

        blockedCommands.clear();
        for (String c : cfg.getStringList("restrictions.blocked-commands")) {
            blockedCommands.add(c.toLowerCase(Locale.ROOT).replace("/", ""));
        }

        blockedCauses.clear();
        for (String c : cfg.getStringList("restrictions.blocked-teleport-causes")) {
            try {
                blockedCauses.add(TeleportCause.valueOf(c.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown teleport cause in config: " + c);
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration d = YamlConfiguration.loadConfiguration(dataFile);
        lastTrigger = d.getString("last-trigger", "");
        lastCrownCreated = d.getLong("last-crown-created", 0L);
        eventActive = d.getBoolean("event-active");
        huntActive = d.getBoolean("hunt-active");
        chestLooted = d.getBoolean("chest-looted");
        endsAt = d.getLong("ends-at");
        startedAt = d.getLong("started-at");
        String w = d.getString("chest.world");
        if (w != null && Bukkit.getWorld(w) != null) {
            chestLocation = new Location(Bukkit.getWorld(w), d.getInt("chest.x"), d.getInt("chest.y"), d.getInt("chest.z"));
        }
    }

    private void saveData() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("last-trigger", lastTrigger);
        d.set("last-crown-created", lastCrownCreated);
        d.set("event-active", eventActive);
        d.set("hunt-active", huntActive);
        d.set("chest-looted", chestLooted);
        d.set("ends-at", endsAt);
        d.set("started-at", startedAt);
        if (chestLocation != null && chestLocation.getWorld() != null) {
            d.set("chest.world", chestLocation.getWorld().getName());
            d.set("chest.x", chestLocation.getBlockX());
            d.set("chest.y", chestLocation.getBlockY());
            d.set("chest.z", chestLocation.getBlockZ());
        }
        try {
            plugin.getDataFolder().mkdirs();
            d.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ messaging

    private TagResolver resolver(String... kv) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.resolver(Placeholder.unparsed(kv[i], kv[i + 1]));
        }
        return b.build();
    }

    private List<Component> lines(String path, String... kv) {
        FileConfiguration cfg = plugin.getConfig();
        String full = "messages." + path;
        List<String> raw = cfg.isList(full) ? cfg.getStringList(full) : List.of(cfg.getString(full, ""));
        String prefix = raw.size() == 1 ? cfg.getString("messages.prefix", "") : "";
        TagResolver r = resolver(kv);
        List<Component> out = new ArrayList<>();
        for (String line : raw) {
            out.add(MM.deserialize(prefix + line, r));
        }
        return out;
    }

    public void broadcast(String path, String... kv) {
        for (Component c : lines(path, kv)) {
            Bukkit.broadcast(c);
        }
    }

    public void send(CommandSender to, String path, String... kv) {
        for (Component c : lines(path, kv)) {
            to.sendMessage(c);
        }
    }

    public void actionBar(Player p, String path) {
        List<Component> l = lines(path);
        if (!l.isEmpty()) {
            p.sendActionBar(l.get(0));
        }
    }

    // ------------------------------------------------------------------ state accessors

    public boolean isEventActive() {
        return eventActive;
    }

    public boolean isHuntActive() {
        return huntActive;
    }

    public Set<String> blockedCommands() {
        return blockedCommands;
    }

    public Set<TeleportCause> blockedCauses() {
        return blockedCauses;
    }

    public List<Town> towns() {
        return towns;
    }

    public Location chestLocation() {
        return chestLocation;
    }

    public UUID carrier() {
        return carrier;
    }

    public boolean isRestricted(Player p) {
        if (p.hasPermission("crownevent.bypass")) {
            return false;
        }
        if (eventActive && plugin.getConfig().getBoolean("restrictions.block-all-players-during-event", true)) {
            return true;
        }
        return huntActive && p.getUniqueId().equals(carrier);
    }

    /** True while the event chest still holds an untouched Crown. */
    public boolean isChestProtected(Location loc) {
        if (chestLocation == null || !huntActive || chestLooted || loc.getWorld() == null) {
            return false;
        }
        return loc.getWorld().equals(chestLocation.getWorld())
                && loc.getBlockX() == chestLocation.getBlockX()
                && loc.getBlockY() == chestLocation.getBlockY()
                && loc.getBlockZ() == chestLocation.getBlockZ();
    }

    public boolean isEventChest(Location loc) {
        if (chestLocation == null || loc == null || loc.getWorld() == null) {
            return false;
        }
        return loc.getWorld().equals(chestLocation.getWorld())
                && loc.getBlockX() == chestLocation.getBlockX()
                && loc.getBlockY() == chestLocation.getBlockY()
                && loc.getBlockZ() == chestLocation.getBlockZ();
    }

    // ------------------------------------------------------------------ crown creation

    public ItemStack newCrown(boolean eventCrown) {
        ItemStack item = crown.create(eventCrown);
        long now = System.currentTimeMillis();
        lastCrownCreated = now;
        saveData();
        scheduleExactScan(now + crown.lifespanMillis());
        return item;
    }

    // ------------------------------------------------------------------ weekly schedule

    private void scheduleTick() {
        FileConfiguration cfg = plugin.getConfig();
        if (eventActive && System.currentTimeMillis() >= endsAt) {
            endEventWindow();
        }

        ZonedDateTime now = ZonedDateTime.now(plugin.zone());
        DayOfWeek day;
        LocalTime target;
        try {
            day = DayOfWeek.valueOf(cfg.getString("schedule.day", "SUNDAY").toUpperCase(Locale.ROOT));
            target = LocalTime.parse(cfg.getString("schedule.time", "18:00"));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid schedule.day / schedule.time in config.yml");
            return;
        }
        if (now.getDayOfWeek() != day) {
            return;
        }
        LocalTime nowTime = now.toLocalTime();
        if (nowTime.isBefore(target)) {
            return;
        }
        long late = Duration.between(target, nowTime).toMinutes();
        if (late > cfg.getLong("schedule.grace-minutes", 30L)) {
            return;
        }
        String today = LocalDate.from(now).toString();
        if (today.equals(lastTrigger)) {
            return;
        }
        lastTrigger = today;
        saveData();
        startEvent();
    }

    // ------------------------------------------------------------------ start / stop

    /** @return false if an event spawn is already in progress or the world is invalid. */
    public boolean startEvent() {
        if (spawning) {
            return false;
        }
        World world = Bukkit.getWorld(plugin.getConfig().getString("event.world", "world"));
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            plugin.getLogger().warning("event.world is missing or not a NORMAL world; event not started.");
            return false;
        }
        spawning = true;
        removeChest();
        findLocation(world, 0, loc -> {
            spawning = false;
            if (loc == null) {
                plugin.getLogger().warning("Could not find a safe Crown chest location after several attempts.");
                return;
            }
            placeChest(loc);
        });
        return true;
    }

    private void placeChest(Location loc) {
        FileConfiguration cfg = plugin.getConfig();
        Block b = loc.getBlock();
        b.setType(Material.CHEST, false);
        if (b.getState() instanceof Chest chest) {
            chest.getInventory().addItem(newCrown(true));
        }
        chestLocation = b.getLocation();
        long now = System.currentTimeMillis();
        eventActive = true;
        huntActive = true;
        chestLooted = false;
        carrier = null;
        startedAt = now;
        endsAt = now + cfg.getLong("event.duration-minutes", 120L) * 60_000L;
        saveData();

        if (cfg.getBoolean("restrictions.force-pvp", true)) {
            Set<World> worlds = new HashSet<>();
            worlds.add(loc.getWorld());
            for (Town t : towns) {
                World w = Bukkit.getWorld(t.world());
                if (w != null) {
                    worlds.add(w);
                }
            }
            for (World w : worlds) {
                w.setPVP(true);
            }
        }

        broadcast("event-start",
                "custom_message", cfg.getString("messages.custom-event-message", ""),
                "x", String.valueOf(b.getX()), "y", String.valueOf(b.getY()), "z", String.valueOf(b.getZ()),
                "world", b.getWorld().getName());
    }

    private void endEventWindow() {
        eventActive = false;
        saveData();
        broadcast("event-window-ended");
    }

    public void stopEvent() {
        boolean was = eventActive || huntActive;
        eventActive = false;
        huntActive = false;
        carrier = null;
        removeChest();
        saveData();
        if (was) {
            broadcast("event-stopped");
        }
    }

    private void removeChest() {
        if (chestLocation == null) {
            return;
        }
        Location loc = chestLocation;
        chestLocation = null;
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        w.getChunkAtAsync(loc).thenAccept(ch -> Bukkit.getScheduler().runTask(plugin, () -> {
            Block b = loc.getBlock();
            if (b.getType() != Material.CHEST || !(b.getState() instanceof Chest chest)) {
                return;
            }
            Inventory inv = chest.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (crown.isCrown(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            boolean empty = true;
            for (ItemStack it : inv.getContents()) {
                if (it != null && !it.getType().isAir()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                b.setType(Material.AIR, false);
            }
        }));
    }

    // ------------------------------------------------------------------ location search (async chunk loading)

    private void findLocation(World world, int attempt, Consumer<Location> done) {
        FileConfiguration cfg = plugin.getConfig();
        if (attempt >= cfg.getInt("event.max-attempts", 40)) {
            done.accept(null);
            return;
        }
        WorldBorder border = world.getWorldBorder();
        Location c = border.getCenter();
        double half = border.getSize() / 2.0 - cfg.getDouble("event.border-margin", 100.0);
        double maxR = Math.min(half, cfg.getDouble("event.max-radius", 3000.0));
        if (maxR < 50) {
            done.accept(null);
            return;
        }
        double minR = Math.min(cfg.getDouble("event.min-radius", 800.0), maxR * 0.8);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble(Math.PI * 2);
        double dist = minR + rnd.nextDouble() * (maxR - minR);
        int x = (int) Math.floor(c.getX() + Math.cos(ang) * dist);
        int z = (int) Math.floor(c.getZ() + Math.sin(ang) * dist);

        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Location result = null;
                    if (err == null) {
                        try {
                            result = validate(world, x, z);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Location check failed: " + ex.getMessage());
                        }
                    }
                    if (result != null) {
                        done.accept(result);
                    } else {
                        findLocation(world, attempt + 1, done);
                    }
                }));
    }

    private Location validate(World world, int x, int z) {
        Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!ground.getType().isSolid() || ground.isLiquid() || UNSAFE.contains(ground.getType())) {
            return null;
        }
        Block above = ground.getRelative(BlockFace.UP);
        if (!above.isEmpty() || !above.getRelative(BlockFace.UP).isEmpty()) {
            return null;
        }
        String biome = ground.getBiome().getKey().getKey();
        if (biome.contains("ocean")) {
            return null;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (ground.getRelative(dx, dy, dz).getType() == Material.LAVA) {
                        return null;
                    }
                }
            }
        }
        Location loc = above.getLocation();
        if (!world.getWorldBorder().isInside(loc)) {
            return null;
        }
        double minTown = plugin.getConfig().getDouble("event.min-distance-from-towns", 600.0);
        for (Town t : towns) {
            if (t.world().equals(world.getName()) && Math.hypot(t.x() - x, t.z() - z) < minTown) {
                return null;
            }
        }
        return loc;
    }

    // ------------------------------------------------------------------ carrier tracking & delivery

    public boolean holdsEventCrown(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (crown.isEventCrown(it)) {
                return true;
            }
        }
        return crown.isEventCrown(p.getItemOnCursor());
    }

    private void watchCarrier() {
        if (!huntActive) {
            return;
        }
        if (System.currentTimeMillis() > startedAt + crown.lifespanMillis()) {
            huntActive = false;
            carrier = null;
            saveData();
            return;
        }
        Player holder = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isDead() && holdsEventCrown(p)) {
                holder = p;
                break;
            }
        }
        if (holder == null) {
            carrier = null;
            return;
        }
        if (!holder.getUniqueId().equals(carrier)) {
            carrier = holder.getUniqueId();
            if (!chestLooted) {
                chestLooted = true;
                saveData();
                broadcast("crown-taken-from-chest", "player", holder.getName());
            } else {
                broadcast("crown-picked-up", "player", holder.getName());
            }
        }
        Town t = townAt(holder.getLocation());
        if (t != null) {
            deliver(holder, t);
        }
    }

    public Town townAt(Location l) {
        World w = l.getWorld();
        if (w == null) {
            return null;
        }
        boolean vertical = plugin.getConfig().getBoolean("delivery.check-vertical", false);
        for (Town t : towns) {
            if (!t.world().equals(w.getName())) {
                continue;
            }
            double dx = l.getX() - t.x();
            double dz = l.getZ() - t.z();
            double dy = vertical ? l.getY() - t.y() : 0.0;
            if (dx * dx + dy * dy + dz * dz <= t.radius() * t.radius()) {
                return t;
            }
        }
        return null;
    }

    private void deliver(Player p, Town t) {
        huntActive = false;
        eventActive = false;
        carrier = null;
        saveData();
        broadcast("crown-delivered", "player", p.getName(), "town", t.name());
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    public void onCarrierDeath(Player victim, Player killer) {
        carrier = null;
        Location l = victim.getLocation();
        String[] coords = {"x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()),
                "z", String.valueOf(l.getBlockZ())};
        if (killer != null) {
            broadcast("crown-dropped-killed", concat(coords, "player", victim.getName(), "killer", killer.getName()));
        } else {
            broadcast("crown-dropped-died", concat(coords, "player", victim.getName()));
        }
    }

    public void onCarrierQuit(Player p) {
        List<ItemStack> crowns = extractEventCrowns(p);
        if (crowns.isEmpty()) {
            return;
        }
        Location l = p.getLocation();
        for (ItemStack it : crowns) {
            l.getWorld().dropItemNaturally(l, it);
        }
        carrier = null;
        broadcast("crown-dropped-logout",
                "player", p.getName(), "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()),
                "z", String.valueOf(l.getBlockZ()), "world", l.getWorld().getName());
    }

    private List<ItemStack> extractEventCrowns(Player p) {
        List<ItemStack> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (crown.isEventCrown(it)) {
                out.add(it);
                inv.setItem(i, null);
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (crown.isEventCrown(cursor)) {
            out.add(cursor);
            p.setItemOnCursor(null);
        }
        return out;
    }

    private static String[] concat(String[] a, String... b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ------------------------------------------------------------------ crown effects + particle ring

    private void visualTick() {
        tick++;
        boolean refresh = tick % 5 == 0;
        boolean sparkle = tick % 2 == 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isGliding() && isRestricted(p)) {
                p.setGliding(false);
                actionBar(p, "blocked-elytra");
            }
            ItemStack helmet = p.getInventory().getHelmet();
            boolean wearing = helmet != null && helmet.getType() == Material.GOLDEN_HELMET
                    && crown.isCrown(helmet) && !crown.isExpired(helmet) && !p.isDead();
            UUID id = p.getUniqueId();
            if (wearing) {
                wearers.add(id);
                if (refresh) {
                    applyEffects(p);
                }
                spawnCrown(p, sparkle);
            } else if (wearers.remove(id)) {
                removeEffects(p);
            }
        }
    }

    private void applyEffects(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 100, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0, true, false, true));
    }

    /** Only removes effects that look like ours (short remaining duration + matching amplifier). */
    private void removeEffects(Player p) {
        strip(p, PotionEffectType.HASTE, 1);
        strip(p, PotionEffectType.SPEED, 0);
        strip(p, PotionEffectType.STRENGTH, 0);
        strip(p, PotionEffectType.REGENERATION, 0);
    }

    private void strip(Player p, PotionEffectType type, int amp) {
        PotionEffect e = p.getPotionEffect(type);
        if (e != null && e.getAmplifier() == amp && e.getDuration() <= 100) {
            p.removePotionEffect(type);
        }
    }

    public void cleanup(Player p) {
        if (wearers.remove(p.getUniqueId())) {
            removeEffects(p);
        }
    }

    private void spawnCrown(Player p, boolean sparkle) {
        FileConfiguration cfg = plugin.getConfig();
        double range = cfg.getDouble("crown.particles.view-distance", 48.0);
        boolean self = cfg.getBoolean("crown.particles.show-to-wearer", false);

        List<Player> viewers = new ArrayList<>();
        for (Player v : p.getWorld().getNearbyPlayers(p.getLocation(), range)) {
            if (self || !v.getUniqueId().equals(p.getUniqueId())) {
                viewers.add(v);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }

        Location base = p.getLocation().add(0, p.getHeight() + 0.2, 0);
        final int points = 20;           // 5 prongs x 4 points
        final double radius = 0.42;
        double rot = tick * 0.12;

        ParticleBuilder dust = new ParticleBuilder(Particle.DUST)
                .count(1).extra(0).data(GOLD).receivers(viewers);

        Location tipForSparkle = null;
        int sparkleIndex = ThreadLocalRandom.current().nextInt(5) * 4;
        for (int i = 0; i < points; i++) {
            double ang = (2 * Math.PI * i / points) + rot;
            int m = i % 4;
            double y = (m == 0) ? 0.30 : (m == 2 ? 0.0 : 0.15);   // zig-zag = crown prongs
            Location l = base.clone().add(Math.cos(ang) * radius, y, Math.sin(ang) * radius);
            dust.location(l).spawn();
            if (i == sparkleIndex) {
                tipForSparkle = l;
            }
        }

        if (sparkle && tipForSparkle != null) {
            new ParticleBuilder(Particle.END_ROD).location(tipForSparkle).count(1)
                    .offset(0.02, 0.02, 0.02).extra(0.003).receivers(viewers).spawn();
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            Location w = base.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            new ParticleBuilder(Particle.WAX_ON).location(w).count(1)
                    .offset(0.05, 0.05, 0.05).extra(0).receivers(viewers).spawn();
        }
    }

    // ------------------------------------------------------------------ 6-day expiry

    private boolean hasEverCreated() {
        return lastCrownCreated > 0;
    }

    /** The heavy global scan only runs while a crown can still be alive; lazy hooks cover leftovers afterwards. */
    private boolean crownMayExist() {
        return hasEverCreated()
                && System.currentTimeMillis() < lastCrownCreated + crown.lifespanMillis() + 600_000L;
    }

    private void scheduleExactScan(long expiresAt) {
        long delay = (expiresAt - System.currentTimeMillis()) / 50L + 1L;
        if (delay < 1L) {
            delay = 1L;
        }
        if (delay > Integer.MAX_VALUE) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::expiryScan, delay);
    }

    public void expiryScan() {
        if (!crownMayExist()) {
            return;
        }
        int removed = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            removed += purge(p.getInventory());
            removed += purge(p.getEnderChest());
            if (crown.isExpired(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
                removed++;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                removed += purgeChunk(c);
            }
            for (Entity e : w.getEntities()) {
                removed += purgeEntity(e);
            }
        }
        notifyExpired(removed);
    }

    public int purge(Inventory inv) {
        if (!hasEverCreated() || inv == null) {
            return 0;
        }
        int removed = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && crown.isExpired(it)) {
                inv.setItem(i, null);
                removed++;
            }
        }
        return removed;
    }

    public int purgeChunk(Chunk chunk) {
        if (!hasEverCreated()) {
            return 0;
        }
        int removed = 0;
        for (BlockState s : chunk.getTileEntities(false)) {
            if (s instanceof Container c) {
                removed += purge(c.getInventory());
            }
        }
        return removed;
    }

    public int purgeEntity(Entity e) {
        if (!hasEverCreated() || e instanceof Player) {
            return 0;
        }
        int removed = 0;
        if (e instanceof Item item) {
            if (crown.isExpired(item.getItemStack())) {
                item.remove();
                return 1;
            }
            return 0;
        }
        if (e instanceof ItemFrame frame && crown.isExpired(frame.getItem())) {
            frame.setItem(null);
            removed++;
        }
        if (e instanceof LivingEntity le) {
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack it = eq.getItem(slot);
                    if (crown.isExpired(it)) {
                        eq.setItem(slot, null);
                        removed++;
                    }
                }
            }
        }
        if (e instanceof InventoryHolder holder) {
            removed += purge(holder.getInventory());
        }
        return removed;
    }

    public void purgeEntities(Collection<? extends Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            removed += purgeEntity(e);
        }
        notifyExpired(removed);
    }

    public void notifyExpired(int removed) {
        long now = System.currentTimeMillis();
        if (removed > 0 && now - lastExpiredMsg > 10_000L) {
            lastExpiredMsg = now;
            broadcast("crown-expired");
        }
    }

    // ------------------------------------------------------------------ status (for /crownevent status)

    public String statusLine() {
        long now = System.currentTimeMillis();
        String chest = chestLocation == null ? "none"
                : chestLocation.getWorld().getName() + " " + chestLocation.getBlockX() + " "
                + chestLocation.getBlockY() + " " + chestLocation.getBlockZ();
        Player c = carrier == null ? null : Bukkit.getPlayer(carrier);
        return "eventActive=" + eventActive + ", huntActive=" + huntActive + ", chestLooted=" + chestLooted
                + ", chest=" + chest + ", carrier=" + (c == null ? "none" : c.getName())
                + ", windowEndsIn=" + (eventActive ? Math.max(0, (endsAt - now) / 60_000L) + "min" : "-");
    }
}
