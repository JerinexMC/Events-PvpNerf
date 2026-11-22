package com.casual.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PrizeManager implements Listener {

    private final CasualEventsPlugin plugin;
    private final File file;
    private FileConfiguration config;
    private final List<ItemStack> prizes = new ArrayList<>();
    private final Random random = new Random();
    private static final String GUI_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Event Prize Pool";

    public PrizeManager(CasualEventsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prizes.yml");
        loadPrizes();
    }

    public void openPrizeEditor(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE);
        for (int i = 0; i < Math.min(prizes.size(), 54); i++) {
            inv.setItem(i, prizes.get(i).clone());
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;

        prizes.clear();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                prizes.add(item.clone());
            }
        }
        savePrizes();
        if (event.getPlayer() instanceof Player p) {
            p.sendMessage(ChatColor.GREEN + "Saved " + prizes.size() + " prize item(s) to the event pool!");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    public void giveRandomPrize(Player winner) {
        if (prizes.isEmpty()) {
            winner.sendMessage(ChatColor.YELLOW + "No prizes configured yet! Ask an admin to run /bounty event prize.");
            return;
        }
        ItemStack reward = prizes.get(random.nextInt(prizes.size())).clone();
        winner.getInventory().addItem(reward).values().forEach(leftover ->
                winner.getWorld().dropItemNaturally(winner.getLocation(), leftover));

        String itemName = reward.hasItemMeta() && reward.getItemMeta().hasDisplayName()
                ? reward.getItemMeta().getDisplayName()
                : reward.getType().name().replace("_", " ");

        winner.sendMessage(ChatColor.GOLD + "★ You won a random event prize: " + ChatColor.AQUA + reward.getAmount() + "x " + itemName + ChatColor.GOLD + "!");
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void loadPrizes() {
        if (!file.exists()) return;
        config = YamlConfiguration.loadConfiguration(file);
        List<?> list = config.getList("prizes");
        prizes.clear();
        if (list != null) {
            for (Object obj : list) {
                if (obj instanceof ItemStack item) {
                    prizes.add(item);
                }
            }
        }
    }

    private void savePrizes() {
        config = new YamlConfiguration();
        config.set("prizes", prizes);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save prizes.yml: " + e.getMessage());
        }
    }
}
