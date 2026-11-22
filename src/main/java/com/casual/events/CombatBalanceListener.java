package com.casual.events;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

public class CombatBalanceListener implements Listener {

    private final CasualEventsPlugin plugin;

    public CombatBalanceListener(CasualEventsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;

        if (player.hasCooldown(Material.BOW)) {
            event.setCancelled(true);
            return;
        }

        if (hasAnyHarmingArrow(player)) {
            int cooldownSeconds = plugin.getConfig().getInt("balance.harming-bow-cooldown-seconds", 5);
            player.setCooldown(Material.BOW, cooldownSeconds * 20);
            player.sendActionBar(ChatColor.RED + "Harming Arrow Cooldown: " + cooldownSeconds + "s");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon.getType() == Material.MACE) {
            double multiplier = plugin.getConfig().getDouble("balance.mace-damage-multiplier", 0.5);
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    private boolean hasAnyHarmingArrow(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isHarmingArrow(item)) return true;
        }
        return false;
    }

    private boolean isHarmingArrow(ItemStack item) {
        if (item == null || item.getType() != Material.TIPPED_ARROW) return false;
        if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;

        PotionType baseType = meta.getBasePotionType();
        if (baseType == PotionType.HARMING || baseType == PotionType.STRONG_HARMING) {
            return true;
        }
        for (PotionEffect custom : meta.getCustomEffects()) {
            if (custom.getType().equals(PotionEffectType.INSTANT_DAMAGE)) {
                return true;
            }
        }
        return false;
    }
}
