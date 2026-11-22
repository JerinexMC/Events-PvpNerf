package com.casual.events;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class EventManager implements Listener {

    public enum EventType {
        NONE, BOUNTY, INVIS, VISUAL, BOSS, METEOR, LOWGRAV, FRENZY
    }

    private final CasualEventsPlugin plugin;
    private final PrizeManager prizeManager;
    private final NamespacedKey compassKey;
    private final Random random = new Random();

    private EventType activeEvent = EventType.NONE;
    private final List<BukkitTask> activeTasks = new ArrayList<>();
    private BossBar bossBar;

    // Bounty state
    private final Map<UUID, UUID> bountyTargets = new HashMap<>();
    private final Map<UUID, Location> lastSafeTargetLoc = new HashMap<>();

    // Boss state
    private UUID bossUuid;
    private final Map<UUID, Double> bossDamageMap = new HashMap<>();

    // Non-griefing meteor crater state (stores original BlockData for restoration)
    private final Map<Location, BlockData> originalCraterBlocks = new LinkedHashMap<>();

    public EventManager(CasualEventsPlugin plugin, PrizeManager prizeManager) {
        this.plugin = plugin;
        this.prizeManager = prizeManager;
        this.compassKey = new NamespacedKey(plugin, "bounty_compass");
    }

    public EventType getActiveEvent() {
        return activeEvent;
    }

    public void stopCurrentEvent(boolean announce) {
        for (BukkitTask task : activeTasks) {
            task.cancel();
        }
        activeTasks.clear();

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        if (activeEvent == EventType.BOUNTY) {
            removeAllBountyCompasses();
            bountyTargets.clear();
            lastSafeTargetLoc.clear();
        } else if (activeEvent == EventType.INVIS) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        } else if (activeEvent == EventType.BOSS && bossUuid != null) {
            Player boss = Bukkit.getPlayer(bossUuid);
            if (boss != null) {
                resetBossStats(boss);
            }
            bossUuid = null;
            bossDamageMap.clear();
        } else if (activeEvent == EventType.LOWGRAV) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.removePotionEffect(PotionEffectType.JUMP_BOOST);
                p.removePotionEffect(PotionEffectType.SLOW_FALLING);
            }
        }

        restoreMeteorCraters();

        if (announce && activeEvent != EventType.NONE) {
            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[EVENT] " + ChatColor.GRAY + "The active event (" + activeEvent.name() + ") has ended!");
        }
        activeEvent = EventType.NONE;
    }

    // ==================== 1. BOUNTY HUNT ====================
    public void startBountyEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.BOUNTY;

        int durationMins = plugin.getConfig().getInt("bounty.duration-minutes", 30);
        int maxDist = plugin.getConfig().getInt("bounty.max-distance-from-spawn", 300);

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.size() < 2) {
            Bukkit.broadcastMessage(ChatColor.RED + "Need at least 2 players online for Bounty Hunt!");
            activeEvent = EventType.NONE;
            return;
        }

        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            Player hunter = players.get(i);
            Player target = players.get((i + 1) % players.size());
            assignBounty(hunter, target);
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "⚔ BOUNTY HUNT STARTED! ⚔");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Track your target within " + maxDist + " blocks of spawn for " + durationMins + " minutes!");
        playSoundAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f);

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                if (activeEvent != EventType.BOUNTY) {
                    cancel();
                    return;
                }
                for (Player hunter : Bukkit.getOnlinePlayers()) {
                    UUID targetId = bountyTargets.get(hunter.getUniqueId());
                    if (targetId == null) continue;
                    Player target = Bukkit.getPlayer(targetId);
                    if (target == null || !target.isOnline()) continue;

                    Location spawn = target.getWorld().getSpawnLocation();
                    double distFromSpawn = Math.hypot(
                            target.getLocation().getX() - spawn.getX(),
                            target.getLocation().getZ() - spawn.getZ()
                    );

                    boolean insideZone = distFromSpawn <= maxDist;
                    if (insideZone) {
                        lastSafeTargetLoc.put(target.getUniqueId(), target.getLocation().clone());
                    }

                    Location trackLoc = lastSafeTargetLoc.getOrDefault(target.getUniqueId(), spawn);
                    updatePlayerCompass(hunter, target, trackLoc, insideZone);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L));

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[BOUNTY] " + ChatColor.YELLOW + "Time's up! Removing all bounty compasses.");
                stopCurrentEvent(false);
            }
        }.runTaskLater(plugin, durationMins * 60L * 20L));
    }

    private void assignBounty(Player hunter, Player target) {
        bountyTargets.put(hunter.getUniqueId(), target.getUniqueId());
        Location spawn = target.getWorld().getSpawnLocation();
        double dist = Math.hypot(target.getLocation().getX() - spawn.getX(), target.getLocation().getZ() - spawn.getZ());
        if (dist <= plugin.getConfig().getInt("bounty.max-distance-from-spawn", 300)) {
            lastSafeTargetLoc.put(target.getUniqueId(), target.getLocation().clone());
        } else {
            lastSafeTargetLoc.put(target.getUniqueId(), spawn);
        }

        removeBountyCompassFrom(hunter);
        hunter.getInventory().addItem(createBountyCompass(target.getName(), lastSafeTargetLoc.get(target.getUniqueId())));
        hunter.sendMessage(ChatColor.GOLD + "Your Bounty Target is: " + ChatColor.RED + "" + ChatColor.BOLD + target.getName());
    }

    private ItemStack createBountyCompass(String targetName, Location loc) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Bounty Tracker: " + ChatColor.GOLD + targetName);
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Points to your target within 300 blocks of spawn.",
                ChatColor.DARK_RED + "Vanishes if you die!"
        ));
        meta.setLodestone(loc);
        meta.setLodestoneTracked(false);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        return compass;
    }

    private void updatePlayerCompass(Player hunter, Player target, Location trackLoc, boolean insideZone) {
        for (ItemStack item : hunter.getInventory().getContents()) {
            if (isBountyCompass(item)) {
                CompassMeta meta = (CompassMeta) item.getItemMeta();
                meta.setLodestone(trackLoc);
                meta.setLodestoneTracked(false);
                if (insideZone) {
                    int blocks = (int) hunter.getLocation().distance(trackLoc);
                    meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Target: " + ChatColor.GOLD + target.getName() + ChatColor.GRAY + " (" + blocks + "m)");
                } else {
                    meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Target: " + ChatColor.GOLD + target.getName() + ChatColor.DARK_RED + " [OUTSIDE 300m ZONE]");
                }
                item.setItemMeta(meta);
            }
        }
    }

    private boolean isBountyCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    private void removeBountyCompassFrom(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            if (isBountyCompass(p.getInventory().getItem(i))) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private void removeAllBountyCompasses() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeBountyCompassFrom(p);
        }
    }

    // ==================== 2. PERMANENT INVIS EVENT ====================
    public void startInvisEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.INVIS;
        int mins = plugin.getConfig().getInt("invis.duration-minutes", 10);

        for (Player p : Bukkit.getOnlinePlayers()) {
            applyInvis(p);
        }

        Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "👻 GHOST EVENT STARTED! 👻");
        Bukkit.broadcastMessage(ChatColor.GRAY + "Everyone is permanently invisible for " + mins + " minutes—even after death!");
        playSoundAll(Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f);

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                if (activeEvent != EventType.INVIS) {
                    cancel();
                    return;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                        applyInvis(p);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 40L));

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(ChatColor.AQUA + "[GHOST EVENT] " + ChatColor.YELLOW + "Invisibility has worn off!");
                stopCurrentEvent(false);
            }
        }.runTaskLater(plugin, mins * 60L * 20L));
    }

    private void applyInvis(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, true));
    }

    // ==================== 3. VISUAL CHAOS EVENT ====================
    public void startVisualEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.VISUAL;
        int secs = plugin.getConfig().getInt("visual.duration-seconds", 10);
        int ticks = secs * 20;

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 1, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ticks, 2, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 0.6f);
        }

        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "🌀 SENSORY BLACKOUT! 🌀");
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "Everyone has been blinded and disoriented for " + secs + " seconds!");

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                stopCurrentEvent(false);
            }
        }.runTaskLater(plugin, ticks));
    }

    // ==================== 4. BOSS BATTLE EVENT ====================
    public void startBossEvent(Player boss) {
        stopCurrentEvent(false);
        activeEvent = EventType.BOSS;
        this.bossUuid = boss.getUniqueId();
        this.bossDamageMap.clear();

        double extraHearts = plugin.getConfig().getDouble("boss.extra-hearts", 20.0);
        int announceSecs = plugin.getConfig().getInt("boss.coord-announce-seconds", 15);
        int durationMins = plugin.getConfig().getInt("boss.duration-minutes", 20);

        AttributeInstance maxHp = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(20.0 + extraHearts);
            boss.setHealth(maxHp.getValue());
        }
        boss.setGlowing(true);

        bossBar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "RAID BOSS: " + boss.getName(), BarColor.RED, BarStyle.SEGMENTED_10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☠ BOSS BATTLE STARTED! ☠");
        Bukkit.broadcastMessage(ChatColor.GOLD + boss.getName() + ChatColor.YELLOW + " is now the Raid Boss! Whoever deals the MOST damage wins a prize!");
        playSoundAll(Sound.ENTITY_WITHER_SPAWN, 1.0f);

        activeTasks.add(new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                if (activeEvent != EventType.BOSS) {
                    cancel();
                    return;
                }
                Player b = Bukkit.getPlayer(bossUuid);
                if (b == null || !b.isOnline()) return;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
                }

                AttributeInstance attr = b.getAttribute(Attribute.MAX_HEALTH);
                double max = attr != null ? attr.getValue() : 20.0;
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, b.getHealth() / max)));

                if (counter % announceSecs == 0) {
                    Location l = b.getLocation();
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[BOSS COORDS] " + ChatColor.GOLD + b.getName()
                            + ChatColor.GRAY + " is at " + ChatColor.WHITE + "X: " + l.getBlockX() + ", Y: " + l.getBlockY() + ", Z: " + l.getBlockZ()
                            + ChatColor.GRAY + " (" + l.getWorld().getName() + ")");
                }
                counter++;
            }
        }.runTaskTimer(plugin, 0L, 20L));

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[BOSS] " + ChatColor.YELLOW + "The Boss survived the timer! Event over.");
                stopCurrentEvent(false);
            }
        }.runTaskLater(plugin, durationMins * 60L * 20L));
    }

    private void resetBossStats(Player boss) {
        AttributeInstance maxHp = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(20.0);
            if (boss.getHealth() > 20.0) boss.setHealth(20.0);
        }
        boss.setGlowing(false);
    }

    // ==================== 5. NON-GRIEFING METEOR SHOWER ====================
    public void startMeteorShowerEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.METEOR;

        int waves = plugin.getConfig().getInt("meteor.waves", 6);
        int intervalSecs = plugin.getConfig().getInt("meteor.interval-seconds", 5);
        int spreadRadius = plugin.getConfig().getInt("meteor.spread-radius", 90);
        int craterRadius = plugin.getConfig().getInt("meteor.crater-radius", 3);
        int restoreAfterSecs = plugin.getConfig().getInt("meteor.restore-after-seconds", 90);

        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "☄ METEOR SHOWER STARTED! ☄");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Flaming meteors are raining down with scorched craters—zero build grief!");
        playSoundAll(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f);

        activeTasks.add(new BukkitRunnable() {
            int currentWave = 0;

            @Override
            public void run() {
                if (activeEvent != EventType.METEOR || currentWave >= waves) {
                    cancel();
                    return;
                }

                World world = Bukkit.getWorlds().get(0);
                Location center = getMeteorAnchor(world);
                int dx = random.nextInt(spreadRadius * 2 + 1) - spreadRadius;
                int dz = random.nextInt(spreadRadius * 2 + 1) - spreadRadius;
                int targetX = center.getBlockX() + dx;
                int targetZ = center.getBlockZ() + dz;
                int targetY = world.getHighestBlockYAt(targetX, targetZ);
                Location impact = new Location(world, targetX + 0.5, targetY + 1.0, targetZ + 0.5);

                spawnFallingMeteorVisual(impact);
                Bukkit.broadcastMessage(ChatColor.GOLD + "[METEOR] " + ChatColor.YELLOW + "Incoming impact near "
                        + ChatColor.AQUA + "X: " + targetX + ", Y: " + targetY + ", Z: " + targetZ + ChatColor.YELLOW + "!");

                // Impact crater visual after falling delay
                Bukkit.getScheduler().runTaskLater(plugin, () -> triggerVisualMeteorImpact(impact, craterRadius), 25L);
                currentWave++;
            }
        }.runTaskTimer(plugin, 0L, intervalSecs * 20L));

        // Restore scorched crater blocks after restoreAfterSecs
        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                restoreMeteorCraters();
                if (activeEvent == EventType.METEOR) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[METEOR] " + ChatColor.YELLOW + "Meteor shower finished and scorched craters restored!");
                    activeEvent = EventType.NONE;
                }
            }
        }.runTaskLater(plugin, (waves * intervalSecs + restoreAfterSecs) * 20L));
    }

    private Location getMeteorAnchor(World world) {
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            return online.get(random.nextInt(online.size())).getLocation();
        }
        return world.getSpawnLocation();
    }

    private void spawnFallingMeteorVisual(Location impact) {
        World world = impact.getWorld();
        if (world == null) return;

        Location spawnPos = impact.clone().add(random.nextInt(9) - 4, 28, random.nextInt(9) - 4);
        FallingBlock meteor = world.spawnFallingBlock(spawnPos, Material.MAGMA_BLOCK.createBlockData());
        meteor.setDropItem(false);
        meteor.setHurtEntities(false);
        meteor.setVelocity(new Vector(0, -1.2, 0));

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!meteor.isValid() || meteor.isDead() || ticks++ > 35) {
                    cancel();
                    return;
                }
                world.spawnParticle(Particle.FLAME, meteor.getLocation(), 14, 0.5, 0.5, 0.5, 0.04);
                world.spawnParticle(Particle.LARGE_SMOKE, meteor.getLocation(), 8, 0.4, 0.4, 0.4, 0.02);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void triggerVisualMeteorImpact(Location impact, int radius) {
        World world = impact.getWorld();
        if (world == null) return;

        // Visual + sound only (no block breaking / no grief)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 2, 0.5, 0.5, 0.5, 0.0);
        world.spawnParticle(Particle.FLAME, impact, 70, radius * 0.6, 1.2, radius * 0.6, 0.08);
        world.spawnParticle(Particle.LAVA, impact, 35, radius * 0.5, 0.8, radius * 0.5, 0.05);
        playSoundAll(Sound.ENTITY_GENERIC_EXPLODE, 0.75f);

        int cx = impact.getBlockX();
        int cz = impact.getBlockZ();
        int rSq = radius * radius;

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double distSq = Math.pow(x - cx, 2) + Math.pow(z - cz, 2);
                if (distSq > rSq) continue;

                int topY = world.getHighestBlockYAt(x, z);
                Block surface = world.getBlockAt(x, topY, z);

                // Skip containers, doors, beds, or protected/non-terrain blocks so builds aren't messed up
                if (!isSafeToScorch(surface.getType())) continue;

                Location surfLoc = surface.getLocation();
                originalCraterBlocks.putIfAbsent(surfLoc, surface.getBlockData().clone());

                if (distSq <= 1.2) {
                    surface.setType(Material.MAGMA_BLOCK, false);
                } else if (distSq <= rSq * 0.6) {
                    surface.setType(random.nextBoolean() ? Material.BLACKSTONE : Material.BASALT, false);
                } else {
                    surface.setType(Material.COBBLED_DEEPSLATE, false);
                }

                // Add visual fire on top of scorched blocks without breaking anything
                Block above = world.getBlockAt(x, topY + 1, z);
                if (above.getType().isAir() && distSq <= rSq * 0.75 && random.nextDouble() < 0.65) {
                    Location aboveLoc = above.getLocation();
                    originalCraterBlocks.putIfAbsent(aboveLoc, above.getBlockData().clone());
                    above.setType(Material.FIRE, false);
                }
            }
        }
    }

    private boolean isSafeToScorch(Material type) {
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type == Material.ENDER_CHEST || type == Material.FURNACE || type == Material.BLAST_FURNACE
                || type == Material.SMOKER || type == Material.SHULKER_BOX || type == Material.SPAWNER
                || type == Material.BEDROCK || type == Material.WATER || type == Material.LAVA) {
            return false;
        }
        return type.isSolid();
    }

    private void restoreMeteorCraters() {
        for (Map.Entry<Location, BlockData> entry : originalCraterBlocks.entrySet()) {
            Block block = entry.getKey().getBlock();
            block.setBlockData(entry.getValue(), false);
        }
        originalCraterBlocks.clear();
    }

    // ==================== 6. LOW GRAVITY EVENT ====================
    public void startLowGravEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.LOWGRAV;
        int mins = plugin.getConfig().getInt("lowgrav.duration-minutes", 3);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, mins * 60 * 20, 3, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, mins * 60 * 20, 0, false, false));
        }
        Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "🌙 MOON GRAVITY! 🌙");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Low gravity enabled for " + mins + " minutes!");
        playSoundAll(Sound.ENTITY_PHANTOM_FLAP, 1.0f);

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                stopCurrentEvent(true);
            }
        }.runTaskLater(plugin, mins * 60L * 20L));
    }

    // ==================== 7. DOUBLE DAMAGE FRENZY ====================
    public void startFrenzyEvent() {
        stopCurrentEvent(false);
        activeEvent = EventType.FRENZY;
        int mins = plugin.getConfig().getInt("frenzy.duration-minutes", 2);

        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "🔥 DOUBLE DAMAGE FRENZY! 🔥");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "All PvP damage is DOUBLED for " + mins + " minutes!");
        playSoundAll(Sound.ENTITY_RAVAGER_ROAR, 1.0f);

        activeTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                stopCurrentEvent(true);
            }
        }.runTaskLater(plugin, mins * 60L * 20L));
    }

    private void playSoundAll(Sound sound, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1.0f, pitch);
        }
    }

    // ==================== LISTENERS ====================
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        if (activeEvent == EventType.BOUNTY) {
            event.getDrops().removeIf(this::isBountyCompass);
            if (bountyTargets.containsKey(victim.getUniqueId())) {
                bountyTargets.remove(victim.getUniqueId());
                victim.sendMessage(ChatColor.RED + "You died! Your bounty compass vanished.");
            }

            Player killer = victim.getKiller();
            if (killer != null && victim.getUniqueId().equals(bountyTargets.get(killer.getUniqueId()))) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[BOUNTY] " + ChatColor.AQUA + killer.getName()
                        + ChatColor.YELLOW + " eliminated their bounty target " + ChatColor.RED + victim.getName() + ChatColor.YELLOW + "!");
                prizeManager.giveRandomPrize(killer);
                bountyTargets.remove(killer.getUniqueId());
                removeBountyCompassFrom(killer);
            }
        }

        if (activeEvent == EventType.BOSS && victim.getUniqueId().equals(bossUuid)) {
            resetBossStats(victim);
            UUID topDamagerId = bossDamageMap.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (topDamagerId != null && Bukkit.getPlayer(topDamagerId) != null) {
                Player topDamager = Bukkit.getPlayer(topDamagerId);
                double dmg = bossDamageMap.get(topDamagerId);
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[BOSS SLAIN] " + ChatColor.GOLD + victim.getName()
                        + ChatColor.YELLOW + " was defeated! Top damager: " + ChatColor.AQUA + topDamager.getName()
                        + ChatColor.GRAY + " (" + String.format("%.1f", dmg) + " dmg)");
                prizeManager.giveRandomPrize(topDamager);
            } else {
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[BOSS SLAIN] " + ChatColor.YELLOW + "The Raid Boss died!");
            }
            stopCurrentEvent(false);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null || attacker.equals(victim)) return;

        if (activeEvent == EventType.FRENZY) {
            event.setDamage(event.getDamage() * 2.0);
        }

        if (activeEvent == EventType.BOSS && victim.getUniqueId().equals(bossUuid)) {
            bossDamageMap.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (activeEvent == EventType.INVIS) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyInvis(event.getPlayer()), 2L);
        }
    }

    @EventHandler
    public void onMilkConsume(PlayerItemConsumeEvent event) {
        if (activeEvent == EventType.INVIS && event.getItem().getType() == Material.MILK_BUCKET) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyInvis(event.getPlayer()), 1L);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isBountyCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (isBountyCompass(event.getCurrentItem()) && event.getClickedInventory() != event.getWhoClicked().getInventory()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (activeEvent == EventType.BOUNTY) {
            removeBountyCompassFrom(event.getPlayer());
        }
    }
}
