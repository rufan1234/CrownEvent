package com.smp.crown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrownCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("start", "stop", "status", "givecrown", "addtown", "reload");

    private final CrownPlugin plugin;
    private final EventManager mgr;

    public CrownCommand(CrownPlugin plugin, EventManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    private void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("crownevent.admin")) {
            msg(sender, "You do not have permission.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            msg(sender, "Usage: /crownevent <start|stop|status|givecrown <player>|addtown <name> <radius>|reload>",
                    NamedTextColor.YELLOW);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (mgr.startEvent()) {
                    msg(sender, "Searching for a safe location... the event will start in a moment.", NamedTextColor.GREEN);
                } else {
                    msg(sender, "Could not start: a spawn is already in progress or event.world is invalid.",
                            NamedTextColor.RED);
                }
            }
            case "stop" -> {
                mgr.stopEvent();
                msg(sender, "Crown Event stopped.", NamedTextColor.GREEN);
            }
            case "status" -> msg(sender, mgr.statusLine(), NamedTextColor.AQUA);
            case "reload" -> {
                plugin.reloadConfig();
                mgr.reloadCaches();
                msg(sender, "Config reloaded (" + mgr.towns().size() + " towns).", NamedTextColor.GREEN);
            }
            case "givecrown" -> {
                if (args.length < 2) {
                    msg(sender, "Usage: /crownevent givecrown <player>", NamedTextColor.YELLOW);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    msg(sender, "Player not found: " + args[1], NamedTextColor.RED);
                    return true;
                }
                ItemStack item = mgr.newCrown(false);
                Map<Integer, ItemStack> left = target.getInventory().addItem(item);
                for (ItemStack rest : left.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                }
                msg(sender, "Gave a fresh Crown to " + target.getName() + ".", NamedTextColor.GREEN);
            }
            case "addtown" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Only players can use this (it saves your location).", NamedTextColor.RED);
                    return true;
                }
                if (args.length < 3) {
                    msg(sender, "Usage: /crownevent addtown <name> <radius>", NamedTextColor.YELLOW);
                    return true;
                }
                double radius;
                try {
                    radius = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    msg(sender, "Radius must be a number.", NamedTextColor.RED);
                    return true;
                }
                if (radius < 5) {
                    msg(sender, "Radius must be at least 5.", NamedTextColor.RED);
                    return true;
                }
                String key = args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (key.isEmpty()) {
                    msg(sender, "Invalid town name.", NamedTextColor.RED);
                    return true;
                }
                Location l = p.getLocation();
                FileConfiguration cfg = plugin.getConfig();
                String path = "towns." + key + ".";
                cfg.set(path + "name", args[1].replace('_', ' '));
                cfg.set(path + "world", l.getWorld().getName());
                cfg.set(path + "x", Math.round(l.getX() * 10) / 10.0);
                cfg.set(path + "y", Math.round(l.getY() * 10) / 10.0);
                cfg.set(path + "z", Math.round(l.getZ() * 10) / 10.0);
                cfg.set(path + "radius", radius);
                plugin.saveConfig();
                mgr.reloadCaches();
                msg(sender, "Town '" + args[1].replace('_', ' ') + "' saved at your location (radius " + radius + ").",
                        NamedTextColor.GREEN);
            }
            default -> msg(sender, "Unknown sub-command.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("crownevent.admin")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : SUB) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("givecrown")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("addtown")) {
            out.add("60");
        }
        return out;
    }
}
