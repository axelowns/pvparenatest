package dev.pvparena;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PvpArenaPlugin extends JavaPlugin implements TabExecutor {
    private MatchManager matches;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        matches = new MatchManager(this);
        matches.loadWorld();

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);

        PluginCommand cmd = getCommand("pvp");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
    }

    MatchManager matches() {
        return matches;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("pvparena.admin")) {
                sender.sendMessage(Text.msg("<red>You don't have permission to do that."));
                return true;
            }
            matches.stopAll();
            sender.sendMessage(Text.msg("<green>All matches stopped."));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            matches.leaveQueue(p);
            return true;
        }

        if (matches.isInMatch(p)) {
            p.sendMessage(Text.msg("<red>You are already in a match."));
            return true;
        }
        new QueueGui(this, p).open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("leave"));
        if (sender.hasPermission("pvparena.admin")) options.add("stop");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
