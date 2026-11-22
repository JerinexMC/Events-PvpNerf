package com.casual.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CasualEventsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private PrizeManager prizeManager;
    private EventManager eventManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.prizeManager = new PrizeManager(this);
        this.eventManager = new EventManager(this, prizeManager);

        Bukkit.getPluginManager().registerEvents(prizeManager, this);
        Bukkit.getPluginManager().registerEvents(eventManager, this);
        Bukkit.getPluginManager().registerEvents(new CombatBalanceListener(this), this);

        getCommand("event").setExecutor(this);
        getCommand("event").setTabCompleter(this);
        getCommand("bounty").setExecutor(this);
        getCommand("bounty").setTabCompleter(this);

        getLogger().info("CasualEvents 1.21.11 enabled!");
    }

    @Override
    public void onDisable() {
        if (eventManager != null) {
            eventManager.stopCurrentEvent(false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bounty")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the prize chest.");
                return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("prize")) {
                prizeManager.openPrizeEditor(player);
                return true;
            }
            player.sendMessage(ChatColor.RED + "Usage: /bounty event prize");
            return true;
        }

        if (command.getName().equalsIgnoreCase("event")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /event <start|stop> <bounty|invis|visual|boss|meteor|lowgrav|frenzy> [player]");
                return true;
            }

            if (args[0].equalsIgnoreCase("stop")) {
                eventManager.stopCurrentEvent(true);
                return true;
            }

            if (args[0].equalsIgnoreCase("start") && args.length >= 2) {
                String type = args[1].toLowerCase();
                switch (type) {
                    case "bounty" -> eventManager.startBountyEvent();
                    case "invis" -> eventManager.startInvisEvent();
                    case "visual" -> eventManager.startVisualEvent();
                    case "boss" -> {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Specify a player: /event start boss <player>");
                            return true;
                        }
                        Player target = Bukkit.getPlayer(args[2]);
                        if (target == null) {
                            sender.sendMessage(ChatColor.RED + "Player not found!");
                            return true;
                        }
                        eventManager.startBossEvent(target);
                    }
                    case "meteor" -> eventManager.startMeteorShowerEvent();
                    case "lowgrav" -> eventManager.startLowGravEvent();
                    case "frenzy" -> eventManager.startFrenzyEvent();
                    default -> sender.sendMessage(ChatColor.RED + "Unknown event! Types: bounty, invis, visual, boss, meteor, lowgrav, frenzy");
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bounty")) {
            if (args.length == 1) return List.of("event");
            if (args.length == 2) return List.of("prize");
            return List.of();
        }
        if (command.getName().equalsIgnoreCase("event")) {
            if (args.length == 1) return Arrays.asList("start", "stop");
            if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
                return Arrays.asList("bounty", "invis", "visual", "boss", "meteor", "lowgrav", "frenzy");
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("boss")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return names;
            }
        }
        return List.of();
    }
}
