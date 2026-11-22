package com.cope;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.block.BlockFace;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CopePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int EVIDENCE_LIMIT = 8;
    private final Map<UUID, Double> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> evidence = new ConcurrentHashMap<>();
    private final Map<UUID, ProbeSession> probes = new ConcurrentHashMap<>();
    private final Map<UUID, MovementSample> movement = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movementGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> reachOutliers = new ConcurrentHashMap<>();
    private final Map<String, Long> violationCooldowns = new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private long serverTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("cope"), "cope command missing from plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("cope")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            serverTick++;
            sampleMovement();
            decayViolations();
        }, 1L, 1L);
        getLogger().info("C.O.P.E. enabled. Use /cope help.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(probes.keySet())) finishProbe(id, false, "Plugin disabled");
        violations.clear();
        evidence.clear();
        movement.clear();
        movementGraceUntil.clear();
        reachOutliers.clear();
        violationCooldowns.clear();
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&8[&bC.O.P.E.&8] &r"));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(prefix() + color(message));
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("cope.admin")) return true;
        msg(sender, "&cYou don't have permission.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender, "&bCommands: &f/cope status, check <player>, info <player>, scan <player> [probe]");
            msg(sender, "&bOther: &f/cope alerts, reload, mods allowed|blocked list|add|remove <id>");
            msg(sender, "&7Checks are advisory. Client mod lists cannot be fully read by a vanilla server.");
            return true;
        }
        if (!admin(sender)) return true;

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                msg(sender, "&aConfiguration reloaded. Existing player samples were reset.");
                movement.clear();
                reachOutliers.clear();
            }
            case "status" -> msg(sender, "&7Online: &f" + Bukkit.getOnlinePlayers().size()
                    + " &7| Active sign checks: &f" + probes.size()
                    + " &7| Tracked players: &f" + movement.size());
            case "alerts" -> {
                boolean enabled = !getConfig().getBoolean("settings.alerts", true);
                getConfig().set("settings.alerts", enabled);
                saveConfig();
                msg(sender, "&7Staff alerts: " + (enabled ? "&aON" : "&cOFF"));
            }
            case "check" -> {
                Player target = findTarget(sender, args);
                if (target == null) return true;
                msg(sender, "&b" + target.getName() + " &7| suspicion: &f"
                        + format(violations.getOrDefault(target.getUniqueId(), 0.0)));
                msg(sender, "&7Client brand reported: &f" + Objects.toString(target.getClientBrandName(), "unknown"));
                Set<String> channels = target.getListeningPluginChannels();
                msg(sender, "&7Client-reported plugin channels (&f" + channels.size() + "&7): &f"
                        + (channels.isEmpty() ? "none" : String.join(", ", channels)));
                msg(sender, "&8These signals do not reveal every installed client mod.");
            }
            case "info", "evidence" -> {
                Player target = findTarget(sender, args);
                if (target == null) return true;
                Deque<String> entries = evidence.get(target.getUniqueId());
                msg(sender, "&bRecent evidence for " + target.getName() + " &7(VL "
                        + format(violations.getOrDefault(target.getUniqueId(), 0.0)) + "):");
                if (entries == null || entries.isEmpty()) msg(sender, "&7No recent evidence.");
                else for (String entry : entries) msg(sender, "&8- &7" + entry);
            }
            case "scan" -> {
                Player target = findTarget(sender, args);
                if (target == null) return true;
                String requestedProbe = args.length >= 3 ? args[2] : null;
                startProbe(target, sender, requestedProbe);
            }
            case "mods" -> handleMods(sender, args);
            default -> msg(sender, "&cUnknown command. Use /cope help.");
        }
        return true;
    }

    private Player findTarget(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "&cUsage: /cope " + args[0].toLowerCase(Locale.ROOT) + " <player>");
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) msg(sender, "&cPlayer is not online.");
        return target;
    }

    private void handleMods(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "&7Use /cope mods allowed|blocked list|add|remove <id>");
            return;
        }
        String type = args[1].equalsIgnoreCase("allowed") ? "allowed-mods"
                : args[1].equalsIgnoreCase("blocked") ? "blocked-mods" : null;
        if (type == null) {
            msg(sender, "&cChoose allowed or blocked.");
            return;
        }
        List<String> list = new ArrayList<>(getConfig().getStringList("settings.mod-check." + type));
        if (args.length == 2 || args[2].equalsIgnoreCase("list")) {
            msg(sender, "&7Configured " + type + ": &f" + (list.isEmpty() ? "none" : String.join(", ", list)));
            msg(sender, "&8Note: Paper cannot enumerate all installed client mods; these lists are not proof of installed mods.");
            return;
        }
        if (args.length < 4) {
            msg(sender, "&cUsage: /cope mods " + args[1] + " add|remove <id>");
            return;
        }
        String id = args[3].trim().toLowerCase(Locale.ROOT);
        if (id.isBlank() || id.length() > 128) {
            msg(sender, "&cInvalid identifier.");
            return;
        }
        if (args[2].equalsIgnoreCase("add")) {
            if (!list.contains(id)) list.add(id);
        } else if (args[2].equalsIgnoreCase("remove")) {
            list.remove(id);
        } else {
            msg(sender, "&cUse add, remove, or list.");
            return;
        }
        getConfig().set("settings.mod-check." + type, list);
        saveConfig();
        msg(sender, "&aUpdated " + type + ". Remember: this is a configured list, not a verified mod inventory.");
    }

    private void startProbe(Player player, CommandSender requester, String requestedProbe) {
        if (!getConfig().getBoolean("settings.sign-translation.enabled", false)) {
            msg(requester, "&cSign-translation checks are disabled.");
            return;
        }
        if (probes.containsKey(player.getUniqueId())) {
            msg(requester, "&eThat player is already being checked.");
            return;
        }
        var section = getConfig().getConfigurationSection("settings.sign-translation.probes");
        if (section == null || section.getKeys(false).isEmpty()) {
            msg(requester, "&cNo translation probes configured.");
            return;
        }
        List<String> ids = new ArrayList<>(section.getKeys(false));
        String probeId = requestedProbe == null ? ids.get(0) : requestedProbe;
        if (!ids.contains(probeId)) {
            msg(requester, "&cUnknown probe. Available: &f" + String.join(", ", ids));
            return;
        }
        String base = "settings.sign-translation.probes." + probeId + ".";
        String key = getConfig().getString(base + "translation-key", "");
        String fallback = getConfig().getString(base + "fallback", "COPE_FALLBACK");
        String display = getConfig().getString(base + "display-name", probeId);
        if (key.isBlank()) {
            msg(requester, "&cProbe has no translation key.");
            return;
        }

        Location location = player.getLocation().getBlock().getLocation().add(0, 2, 0);
        if (location.getBlockY() >= player.getWorld().getMaxHeight()) {
            msg(requester, "&cNot enough room above the player to run this check.");
            return;
        }
        Block block = location.getBlock();
        BlockData previous = block.getBlockData().clone();
        probes.put(player.getUniqueId(), new ProbeSession(location, previous, fallback, display));
        try {
            player.sendBlockChange(location, Material.OAK_SIGN.createBlockData());
            player.sendSignChange(location, List.of(
                    Component.translatable(key, fallback), Component.text("COPE_CHECK"),
                    Component.text("COPE_CHECK"), Component.text("COPE_CHECK")));
            player.openVirtualSign(location, Side.FRONT);
            long timeout = Math.max(20L, getConfig().getLong("settings.sign-translation.response-timeout-ticks", 40L));
            Bukkit.getScheduler().runTaskLater(this,
                    () -> finishProbe(player.getUniqueId(), false, "No usable response; inconclusive"), timeout);
            msg(requester, "&eStarted sign translation check for &f" + player.getName()
                    + "&e. This opens a temporary sign editor; use only with player notice.");
        } catch (RuntimeException exception) {
            finishProbe(player.getUniqueId(), false, "Probe unsupported: " + exception.getClass().getSimpleName());
            getLogger().warning("Sign probe failed: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUncheckedSign(UncheckedSignChangeEvent event) {
        ProbeSession session = probes.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        Location at = session.location();
        var pos = event.getEditedBlockPosition();
        if (pos.blockX() != at.getBlockX() || pos.blockY() != at.getBlockY() || pos.blockZ() != at.getBlockZ()) return;
        event.setCancelled(true);
        String first = event.lines().isEmpty() ? "" : plain.serialize(event.lines().get(0)).trim();
        boolean translated = !first.equals(session.fallback()) && !first.isBlank();
        finishProbe(event.getPlayer().getUniqueId(), translated,
                translated ? "Configured translation key resolved; client signal only" : "Fallback text returned; no match");
    }

    private void finishProbe(UUID id, boolean translated, String detail) {
        ProbeSession session = probes.remove(id);
        if (session == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline() && player.getWorld().equals(session.location().getWorld())) {
            player.sendBlockChange(session.location(), session.previous());
        }
        String result = translated ? "&eCLIENT SIGNAL" : "&7INCONCLUSIVE / NO MATCH";
        String line = "&7Sign check for &f" + (player == null ? id : player.getName())
                + "&7: " + result + " &8(" + detail + ")";
        alertStaff(line);
        getLogger().info("Sign check " + id + ": " + (translated ? "CLIENT SIGNAL" : "INCONCLUSIVE") + " - " + detail);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        movement.put(player.getUniqueId(), new MovementSample(player.getLocation().clone(), 0));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            checkClientSignals(player);
            if (player.isOnline()
                    && getConfig().getBoolean("settings.auto-scan-on-join", false)
                    && !getConfig().getBoolean("settings.sign-translation.manual-only", true)) {
                startProbe(player, Bukkit.getConsoleSender(), null);
            }
        }, Math.max(1L, getConfig().getLong("settings.join-scan-delay-ticks", 60L)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (probes.containsKey(id)) finishProbe(id, false, "Player disconnected; inconclusive");
        movement.remove(id);
        movementGraceUntil.remove(id);
        reachOutliers.remove(id);
        violationCooldowns.keySet().removeIf(key -> key.startsWith(id + ":"));
        violations.remove(id);
        evidence.remove(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        movementGraceUntil.put(event.getPlayer().getUniqueId(), serverTick + 20L);
        movement.put(event.getPlayer().getUniqueId(), new MovementSample(event.getTo().clone(), 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Vector velocity = event.getVelocity();
        if (velocity.lengthSquared() > 0.01) {
            movementGraceUntil.put(event.getPlayer().getUniqueId(), serverTick + 8L);
        }
    }

    private void checkClientSignals(Player player) {
        if (!player.isOnline()) return;
        String brand = player.getClientBrandName();
        if (brand != null && getConfig().getBoolean("settings.client-brand-check.enabled", true)) {
            String normalized = brand.toLowerCase(Locale.ROOT);
            for (String blocked : getConfig().getStringList("settings.client-brand-check.blocked-brands")) {
                if (!blocked.isBlank() && normalized.contains(blocked.toLowerCase(Locale.ROOT))) {
                    addViolation(player, 1.0, "BLOCKED_CLIENT_BRAND_SIGNAL: " + brand);
                    break;
                }
            }
        }
        List<String> blockedChannels = getConfig().getStringList("settings.mod-check.blocked-plugin-channels");
        for (String channel : player.getListeningPluginChannels()) {
            for (String blocked : blockedChannels) {
                if (!blocked.isBlank() && channel.equalsIgnoreCase(blocked)) {
                    addViolation(player, 1.0, "BLOCKED_PLUGIN_CHANNEL_SIGNAL: " + channel);
                    break;
                }
            }
        }
    }

    private void sampleMovement() {
        if (!getConfig().getBoolean("settings.detection.movement.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Location current = player.getLocation();
            MovementSample previous = movement.get(id);
            if (previous == null || !previous.location().getWorld().equals(current.getWorld())) {
                movement.put(id, new MovementSample(current.clone(), 0));
                continue;
            }
            if (isMovementExempt(player) || serverTick <= movementGraceUntil.getOrDefault(id, 0L)) {
                movement.put(id, new MovementSample(current.clone(), 0));
                continue;
            }
            double dx = current.getX() - previous.location().getX();
            double dz = current.getZ() - previous.location().getZ();
            double distance = Math.hypot(dx, dz);
            double maximum = getConfig().getDouble("settings.detection.movement.max-horizontal-distance-per-tick", 1.25);
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
                var effect = player.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
                if (effect != null) maximum += 0.15 * (effect.getAmplifier() + 1);
            }
            int excessTicks = distance > maximum ? previous.excessTicks() + 1 : 0;
            if (excessTicks >= Math.max(2, getConfig().getInt("settings.detection.movement.required-consecutive-ticks", 3))) {
                addViolation(player, getConfig().getDouble("settings.detection.movement.violation-weight", 1.0),
                        "SUSTAINED_MOVEMENT_OUTLIER: " + format(distance) + " blocks in one server tick");
                excessTicks = 0;
            }
            movement.put(id, new MovementSample(current.clone(), excessTicks));
        }
    }

    private boolean isMovementExempt(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying()
                || player.getAllowFlight()
                || player.isInsideVehicle()
                || player.isGliding()
                || player.isSwimming()
                || player.isRiptiding()
                || player.isClimbing()
                || player.isInWater()
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || isSpecialMovementSurface(player);
    }

    private boolean isSpecialMovementSurface(Player player) {
        Material underfoot = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        return underfoot == Material.ICE
                || underfoot == Material.PACKED_ICE
                || underfoot == Material.BLUE_ICE
                || underfoot == Material.FROSTED_ICE
                || underfoot == Material.SLIME_BLOCK
                || underfoot == Material.HONEY_BLOCK
                || underfoot == Material.SOUL_SAND
                || underfoot == Material.SOUL_SOIL;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!getConfig().getBoolean("settings.detection.combat.enabled", true)
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player target)) return;
        if (attacker.getWorld() != target.getWorld() || attacker.isInsideVehicle() || attacker.isGliding()) return;

        Location eye = attacker.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double nearestX = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double nearestY = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double nearestZ = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double distance = eye.distance(new Location(target.getWorld(), nearestX, nearestY, nearestZ));
        double configured = getConfig().getDouble("settings.detection.combat.max-reach", 3.5);
        double buffer = Math.max(0.0, getConfig().getDouble("settings.detection.combat.extra-buffer", 0.35));
        if (distance <= configured + buffer) return;

        long now = System.currentTimeMillis();
        Deque<Long> samples = reachOutliers.computeIfAbsent(attacker.getUniqueId(), ignored -> new ArrayDeque<>());
        synchronized (samples) {
            while (!samples.isEmpty() && now - samples.peekFirst() > 10_000L) samples.removeFirst();
            samples.addLast(now);
            int required = Math.max(2, getConfig().getInt("settings.detection.combat.required-outliers-in-window", 3));
            if (samples.size() >= required) {
                addViolation(attacker, getConfig().getDouble("settings.detection.combat.violation-weight", 1.0),
                        "REPEATED_REACH_OUTLIERS: " + format(distance) + " blocks to target box");
                samples.clear();
            }
        }
    }

    private void addViolation(Player player, double amount, String reason) {
        UUID id = player.getUniqueId();
        int separator = reason.indexOf(':');
        String category = separator < 0 ? reason : reason.substring(0, separator);
        String cooldownKey = id + ":" + category;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, getConfig().getLong("settings.detection.violation-cooldown-ms", 3000L));
        Long previous = violationCooldowns.put(cooldownKey, now);
        if (previous != null && now - previous < cooldown) {
            violationCooldowns.put(cooldownKey, previous);
            return;
        }
        double safeAmount = Math.max(0.0, amount);
        double score = violations.merge(id, safeAmount, Double::sum);
        Deque<String> entries = evidence.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(java.time.LocalTime.now().withNano(0) + " " + reason);
            while (entries.size() > EVIDENCE_LIMIT) entries.removeFirst();
        }
        String line = "&c" + player.getName() + " &7| &f" + reason + " &8| VL: &e" + format(score);
        alertStaff(line);
        getLogger().info(player.getName() + " | " + reason + " | VL=" + format(score));
    }

    private void alertStaff(String line) {
        if (!getConfig().getBoolean("settings.alerts", true)) return;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("cope.alerts")) msg(staff, line);
        }
    }

    private void decayViolations() {
        double decay = Math.max(0.0, getConfig().getDouble("settings.detection.violation-decay-per-second", 0.15));
        if (decay == 0.0) return;
        for (UUID id : new ArrayList<>(violations.keySet())) {
            violations.computeIfPresent(id, (ignored, value) -> Math.max(0.0, value - decay / 20.0));
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return starts(List.of("help", "reload", "status", "check", "info", "evidence", "scan", "alerts", "mods"), args[0]);
        if (args.length == 2 && List.of("check", "info", "evidence", "scan").contains(args[0].toLowerCase(Locale.ROOT))) {
            return starts(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mods")) return starts(List.of("allowed", "blocked"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("mods")) return starts(List.of("add", "remove", "list"), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("scan")) {
            var section = getConfig().getConfigurationSection("settings.sign-translation.probes");
            return section == null ? Collections.emptyList() : starts(new ArrayList<>(section.getKeys(false)), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> starts(List<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private record MovementSample(Location location, int excessTicks) {}
    private record ProbeSession(Location location, BlockData previous, String fallback, String displayName) {}
}
