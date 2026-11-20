package com.smp.crown;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;

public final class CrownPlugin extends JavaPlugin {

    private CrownItem crownItem;
    private EventManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        crownItem = new CrownItem(this);
        manager = new EventManager(this, crownItem);

        getServer().getPluginManager().registerEvents(new CrownListener(this, manager, crownItem), this);

        PluginCommand cmd = getCommand("crownevent");
        if (cmd != null) {
            CrownCommand handler = new CrownCommand(this, manager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.start();
        getLogger().info("CrownEvent enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public ZoneId zone() {
        String s = getConfig().getString("schedule.timezone", "system");
        try {
            return s.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(s);
        } catch (Exception ex) {
            getLogger().warning("Invalid schedule.timezone '" + s + "', using system default.");
            return ZoneId.systemDefault();
        }
    }
}
