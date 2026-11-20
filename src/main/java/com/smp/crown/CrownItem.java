package com.smp.crown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Creates and inspects Crown items. Everything is stored in the PersistentDataContainer. */
public final class CrownItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CrownPlugin plugin;
    private final NamespacedKey keyCrown;
    private final NamespacedKey keyCreated;
    private final NamespacedKey keyEvent;

    public CrownItem(CrownPlugin plugin) {
        this.plugin = plugin;
        this.keyCrown = new NamespacedKey(plugin, "crown");
        this.keyCreated = new NamespacedKey(plugin, "created_at");
        this.keyEvent = new NamespacedKey(plugin, "event_crown");
    }

    public long lifespanMillis() {
        return plugin.getConfig().getLong("crown.lifespan-days", 6L) * 86_400_000L;
    }

    /**
     * @param eventCrown true = crown placed in the event chest (hunt mechanics apply),
     *                   false = admin crown (effects/particles/expiry only)
     */
    public ItemStack create(boolean eventCrown) {
        long now = System.currentTimeMillis();
        ItemStack item = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta meta = item.getItemMeta();

        String expires = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(plugin.zone())
                .format(Instant.ofEpochMilli(now + lifespanMillis()));

        meta.displayName(MM.deserialize(plugin.getConfig().getString("crown.name", "<!italic><gold><bold>Kral Tacı")));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("crown.lore")) {
            lore.add(MM.deserialize(line, Placeholder.unparsed("expires", expires)));
        }
        meta.lore(lore);

        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        meta.addEnchant(Enchantment.PROTECTION, 6, true);
        meta.setEnchantmentGlintOverride(true);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyCrown, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyCreated, PersistentDataType.LONG, now);
        if (eventCrown) {
            pdc.set(keyEvent, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCrown(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_HELMET || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyCrown, PersistentDataType.BYTE);
    }

    public boolean isEventCrown(ItemStack item) {
        if (!isCrown(item)) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyEvent, PersistentDataType.BYTE);
    }

    public long createdAt(ItemStack item) {
        if (!isCrown(item)) {
            return -1L;
        }
        Long v = item.getItemMeta().getPersistentDataContainer().get(keyCreated, PersistentDataType.LONG);
        return v == null ? -1L : v;
    }

    public boolean isExpired(ItemStack item) {
        long created = createdAt(item);
        return created > 0 && System.currentTimeMillis() >= created + lifespanMillis();
    }
}
