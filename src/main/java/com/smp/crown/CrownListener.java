package com.smp.crown;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CrownListener implements Listener {

    private final CrownPlugin plugin;
    private final EventManager mgr;
    private final CrownItem crown;

    public CrownListener(CrownPlugin plugin, EventManager mgr, CrownItem crown) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.crown = crown;
    }

    private static boolean isElytra(ItemStack it) {
        return it != null && it.getType() == Material.ELYTRA;
    }

    // ================================================================== teleport blocking

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String raw = e.getMessage().trim();
        if (raw.length() < 2) {
            return;
        }
        String label = raw.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);      // essentials:tp -> tp
        }
        if (mgr.blockedCommands().contains(label) && mgr.isRestricted(p)) {
            e.setCancelled(true);
            mgr.send(p, "blocked-teleport");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!mgr.blockedCauses().contains(e.getCause())) {
            return;
        }
        Player p = e.getPlayer();
        if (mgr.isRestricted(p)) {
            e.setCancelled(true);
            mgr.actionBar(p, "blocked-teleport");
        }
    }

    // ================================================================== elytra blocking

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && mgr.isRestricted(p)) {
            e.setCancelled(true);
            mgr.actionBar(p, "blocked-elytra");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (isElytra(e.getItem()) && mgr.isRestricted(e.getPlayer())) {
            e.setCancelled(true);                      // prevents right-click equipping
            mgr.actionBar(e.getPlayer(), "blocked-elytra");
        }
    }

    // ================================================================== inventory rules

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Inventory top = e.getView().getTopInventory();
        Inventory clicked = e.getClickedInventory();
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();

        // ---- Crown can never enter an Ender Chest
        if (top.getType() == InventoryType.ENDER_CHEST) {
            boolean intoTop = clicked != null && clicked == top;
            boolean bad = false;
            if (intoTop && crown.isCrown(cursor)) {
                bad = true;
            }
            if (!intoTop && e.isShiftClick() && crown.isCrown(current)) {
                bad = true;
            }
            if (intoTop && e.getClick() == ClickType.NUMBER_KEY
                    && crown.isCrown(p.getInventory().getItem(e.getHotbarButton()))) {
                bad = true;
            }
            if (intoTop && e.getClick() == ClickType.SWAP_OFFHAND
                    && crown.isCrown(p.getInventory().getItemInOffHand())) {
                bad = true;
            }
            if (bad) {
                e.setCancelled(true);
                mgr.send(p, "blocked-ender-chest");
                return;
            }
        }

        // ---- No equipping Elytra while restricted
        if (mgr.isRestricted(p)) {
            boolean armorSlot = e.getSlotType() == InventoryType.SlotType.ARMOR;
            boolean bad = false;
            if (armorSlot && isElytra(cursor)) {
                bad = true;
            }
            if (e.isShiftClick() && isElytra(current) && top.getType() == InventoryType.CRAFTING) {
                bad = true;
            }
            if (armorSlot && e.getClick() == ClickType.NUMBER_KEY
                    && isElytra(p.getInventory().getItem(e.getHotbarButton()))) {
                bad = true;
            }
            if (bad) {
                e.setCancelled(true);
                mgr.actionBar(p, "blocked-elytra");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() != InventoryType.ENDER_CHEST || !crown.isCrown(e.getOldCursor())) {
            return;
        }
        int size = top.getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < size) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    mgr.send(p, "blocked-ender-chest");
                }
                return;
            }
        }
    }

    /** Hoppers must not suck the Crown out of the event chest. */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (crown.isCrown(e.getItem()) && mgr.isEventChest(e.getSource().getLocation())) {
            e.setCancelled(true);
        }
    }

    // ================================================================== event chest protection

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (mgr.isChestProtected(e.getBlock().getLocation())) {
            e.setCancelled(true);
            mgr.send(e.getPlayer(), "chest-protected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> mgr.isChestProtected(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> mgr.isChestProtected(b.getLocation()));
    }

    // ================================================================== dropped crown must never despawn early

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        ItemStack stack = e.getEntity().getItemStack();
        if (crown.isCrown(stack)) {
            e.getEntity().setUnlimitedLifetime(true);   // no 5 min despawn
            e.getEntity().setInvulnerable(true);        // survives fire / lava / explosions / cactus (not the void)
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        if (crown.isExpired(stack)) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    // ================================================================== death: full loot, no restoration

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        boolean carrying = mgr.isHuntActive() && mgr.holdsEventCrown(p);
        boolean fullLoot = carrying || (mgr.isEventActive()
                && plugin.getConfig().getBoolean("restrictions.full-loot-during-event", true));

        if (fullLoot) {
            e.setKeepInventory(false);
            e.getItemsToKeep().clear();
            List<ItemStack> drops = e.getDrops();
            drops.clear();
            for (ItemStack it : p.getInventory().getContents()) {     // storage + armor + offhand
                if (it != null && !it.getType().isAir()) {
                    drops.add(it.clone());
                }
            }
        }
        if (carrying) {
            mgr.onCarrierDeath(p, p.getKiller());
        }
    }

    // ================================================================== join / quit

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (mgr.isHuntActive()) {
            mgr.onCarrierQuit(p);
        }
        mgr.cleanup(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        int removed = mgr.purge(p.getInventory()) + mgr.purge(p.getEnderChest());
        mgr.notifyExpired(removed);
    }

    // ================================================================== lazy expiry hooks (unloaded chunks etc.)

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        mgr.notifyExpired(mgr.purgeChunk(e.getChunk()));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        mgr.purgeEntities(new ArrayList<>(e.getEntities()));
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        mgr.notifyExpired(mgr.purge(e.getInventory()));
    }
}
