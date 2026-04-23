package com.codexmc.servuxcompat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class PaperServuxCompatPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<ServuxPlacementPacket>> pendingPlacements =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastPlainPacketLog = new ConcurrentHashMap<>();
    private final Set<ScheduledTask> scheduledTasks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Set<ScheduledTask>> scheduledTasksByPlayer = new ConcurrentHashMap<>();
    private final LongAdder metadataRequests = new LongAdder();
    private final LongAdder metadataSent = new LongAdder();
    private final LongAdder useItemOnPackets = new LongAdder();
    private final LongAdder encodedPackets = new LongAdder();
    private final LongAdder blockPlaceEvents = new LongAdder();
    private final LongAdder appliedPlacements = new LongAdder();
    private final LongAdder expiredPlacements = new LongAdder();
    private final LongAdder queueOverflowDrops = new LongAdder();

    private ProtocolManager protocolManager;
    private ScheduledExecutorService maintenanceExecutor;
    private volatile boolean debug;
    private volatile Set<String> enabledWorlds = Set.of();
    private volatile boolean metadataEnabled;
    private volatile int joinMetadataDelayTicks;
    private volatile int joinMetadataResendCount;
    private volatile int joinMetadataResendIntervalTicks;
    private volatile boolean easyPlaceV3Enabled;
    private volatile ListenerPriority packetListenerPriority = ListenerPriority.LOWEST;
    private volatile long packetMaxAgeMillis;
    private volatile int maxPendingPlacementsPerPlayer;
    private volatile int maxProtocolValue;
    private volatile boolean strictPlacementMatch;
    private volatile long applyDelayTicks;
    private volatile boolean requireSameMaterialBeforeApply;
    private volatile boolean applyBlockState;
    private volatile boolean asyncMaintenanceEnabled;
    private volatile long asyncMaintenanceIntervalMillis;
    private volatile boolean runtimeActive;

    @Override
    public void onEnable() {
        runtimeActive = false;
        saveDefaultConfig();
        reloadLocalConfig();

        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().severe("ProtocolLib is required before PaperServuxCompat can be enabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        unregisterProtocolListeners();
        unregisterPluginMessagingChannels();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);

        registerPluginMessagingChannels();
        registerPacketListener();

        getServer().getPluginManager().registerEvents(this, this);
        restartMaintenanceExecutor();
        runtimeActive = true;

        if (isPlugManPresent()) {
            getLogger().info("PlugMan/PlugManX detected; explicit reload cleanup is active.");
        }
        getLogger().info("PaperServuxCompat enabled for servux:litematics metadata and Easy Place v3.");
    }

    @Override
    public void onDisable() {
        runtimeActive = false;
        unregisterProtocolListeners();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        unregisterPluginMessagingChannels();
        cancelTrackedTasks();
        stopMaintenanceExecutor();
        pendingPlacements.clear();
        lastPlainPacketLog.clear();
        getLogger().info("Stats: metadata_requests=" + metadataRequests.sum()
                + ", metadata_sent=" + metadataSent.sum()
                + ", use_item_on=" + useItemOnPackets.sum()
                + ", encoded_v3=" + encodedPackets.sum()
                + ", block_place=" + blockPlaceEvents.sum()
                + ", applied=" + appliedPlacements.sum()
                + ", expired=" + expiredPlacements.sum()
                + ", queue_overflow_drops=" + queueOverflowDrops.sum());

        protocolManager = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("servuxpaper")) {
            return false;
        }

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> sendStatus(sender);
            case "resend" -> resendMetadata(sender, args);
            case "reload" -> {
                reloadConfig();
                reloadLocalConfig();
                registerPluginMessagingChannels();
                registerPacketListener();
                restartMaintenanceExecutor();
                sender.sendMessage("[Servux-paper] Config reloaded.");
            }
            default -> sender.sendMessage("[Servux-paper] Usage: /" + label + " <status|resend|reload>");
        }

        return true;
    }

    private void reloadLocalConfig() {
        debug = getConfig().getBoolean("debug", false);
        enabledWorlds = Set.copyOf(getConfig().getStringList("enabled-worlds"));
        metadataEnabled = getConfig().getBoolean("metadata-enabled", true);
        joinMetadataDelayTicks = Math.max(1, getConfig().getInt("join-metadata-delay-ticks", 40));
        joinMetadataResendCount = Math.max(1, getConfig().getInt("join-metadata-resend-count", 4));
        joinMetadataResendIntervalTicks = Math.max(1, getConfig().getInt("join-metadata-resend-interval-ticks", 40));
        easyPlaceV3Enabled = getConfig().getBoolean("easy-place-v3-enabled", true);
        packetListenerPriority = parsePacketListenerPriority(
                getConfig().getString("packet-listener-priority", "LOWEST"));
        packetMaxAgeMillis = Math.max(250L, getConfig().getLong("packet-max-age-millis", 2_500L));
        maxPendingPlacementsPerPlayer = Math.max(4,
                getConfig().getInt("max-pending-placements-per-player", 32));
        maxProtocolValue = Math.max(15, getConfig().getInt("max-protocol-value", 1_048_575));
        strictPlacementMatch = getConfig().getBoolean("strict-placement-match", true);
        applyDelayTicks = Math.max(0L, getConfig().getLong("apply-delay-ticks", 1L));
        requireSameMaterialBeforeApply = getConfig().getBoolean("require-same-material-before-apply", true);
        applyBlockState = getConfig().getBoolean("apply-block-state", true);
        asyncMaintenanceEnabled = getConfig().getBoolean("async-maintenance-enabled", true);
        asyncMaintenanceIntervalMillis = Math.max(250L,
                getConfig().getLong("async-maintenance-interval-millis", 1_000L));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("[Servux-paper] Version: " + getDescription().getVersion());
        sender.sendMessage("[Servux-paper] Metadata: enabled=" + metadataEnabled
                + ", sent=" + metadataSent.sum()
                + ", requests=" + metadataRequests.sum()
                + ", joinResends=" + joinMetadataResendCount);
        sender.sendMessage("[Servux-paper] Easy Place v3: enabled=" + easyPlaceV3Enabled
                + ", packetPriority=" + packetListenerPriority
                + ", use_item_on=" + useItemOnPackets.sum()
                + ", encoded_v3=" + encodedPackets.sum()
                + ", block_place=" + blockPlaceEvents.sum()
                + ", applied=" + appliedPlacements.sum());
        sender.sendMessage("[Servux-paper] Async maintenance: enabled=" + asyncMaintenanceEnabled
                + ", pendingPlayers=" + pendingPlacements.size()
                + ", pendingPackets=" + totalPendingPlacements()
                + ", expired=" + expiredPlacements.sum()
                + ", queueDrops=" + queueOverflowDrops.sum()
                + ", queueLimit=" + maxPendingPlacementsPerPlayer
                + ", blockDataCache=" + ServuxV3PlacementApplier.cacheSize()
                + ", trackedTasks=" + scheduledTasks.size());
        sender.sendMessage("[Servux-paper] PlugMan compatibility: detected=" + isPlugManPresent()
                + ", runtimeActive=" + runtimeActive);

        if (sender instanceof Player player) {
            sender.sendMessage("[Servux-paper] Current world enabled=" + isEnabledFor(player)
                    + ". Use /servuxpaper resend to resend Servux metadata to yourself.");
        }
    }

    private void resendMetadata(CommandSender sender, String[] args) {
        if (!metadataEnabled) {
            sender.sendMessage("[Servux-paper] Metadata is disabled in config.yml.");
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("[Servux-paper] Player is not online: " + args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("[Servux-paper] Console usage: /servuxpaper resend <player>");
            return;
        }

        if (!isEnabledFor(target)) {
            sender.sendMessage("[Servux-paper] Target world is disabled by enabled-worlds.");
            return;
        }

        sendMetadata(target, "manual command");
        sender.sendMessage("[Servux-paper] Metadata resent to " + target.getName() + ".");
    }

    private void registerPacketListener() {
        if (protocolManager == null) {
            return;
        }

        protocolManager.removePacketListeners(this);
        protocolManager.addPacketListener(new PacketAdapter(this, packetListenerPriority,
                PacketType.Play.Client.USE_ITEM_ON) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleUseItemOn(event);
            }
        });
    }

    private void registerPluginMessagingChannels() {
        unregisterPluginMessagingChannels();
        getServer().getMessenger().registerOutgoingPluginChannel(this, ServuxLitematicsPayload.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, ServuxLitematicsPayload.CHANNEL, this);
    }

    private void unregisterPluginMessagingChannels() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    private void unregisterProtocolListeners() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }
    }

    private ListenerPriority parsePacketListenerPriority(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return ListenerPriority.LOWEST;
        }

        try {
            return ListenerPriority.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid packet-listener-priority '" + rawValue + "', using LOWEST.");
            return ListenerPriority.LOWEST;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!runtimeActive || !metadataEnabled || !isEnabledFor(player)) {
            return;
        }

        for (int attempt = 0; attempt < joinMetadataResendCount; attempt++) {
            int attemptNumber = attempt + 1;
            long delay = (long) joinMetadataDelayTicks + (long) attempt * joinMetadataResendIntervalTicks;
            schedulePlayerTask(player,
                    () -> sendMetadata(player, "join " + attemptNumber + "/" + joinMetadataResendCount),
                    delay);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingPlacements.remove(playerId);
        lastPlainPacketLog.remove(playerId);
        cancelPlayerTasks(playerId);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!runtimeActive || !ServuxLitematicsPayload.CHANNEL.equals(channel)
                || !metadataEnabled || !isEnabledFor(player)) {
            return;
        }

        try {
            ServuxLitematicsPayload.IncomingPacket packet = ServuxLitematicsPayload.readPacket(message);
            int packetType = packet.type();
            if (packetType == ServuxLitematicsPayload.PACKET_C2S_METADATA_REQUEST) {
                metadataRequests.increment();
                sendMetadata(player, "metadata request");
            } else {
                debug("Received unsupported servux:litematics packet type=" + packetType
                        + " from " + player.getName() + " length=" + message.length);
            }
        } catch (RuntimeException ex) {
            debug("Could not read servux:litematics payload from " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!runtimeActive) {
            return;
        }

        blockPlaceEvents.increment();
        Player player = event.getPlayer();
        if (!easyPlaceV3Enabled || !applyBlockState || !isEnabledFor(player)) {
            return;
        }

        ServuxPlacementPacket packet = findMatchingPlacement(player.getUniqueId(), event.getBlock(), event.getBlockAgainst());
        if (packet == null || packet.isOlderThanMillis(packetMaxAgeMillis)) {
            debug("BlockPlace without pending v3 packet: player=" + player.getName()
                    + " block=" + event.getBlock().getType() + " at " + event.getBlock().getLocation());
            return;
        }

        Block placed = event.getBlock();
        Block against = event.getBlockAgainst();
        if (!isSamePlacement(packet, placed, against)) {
            debug("Ignoring stale v3 packet for " + player.getName() + " at " + placed.getLocation());
            return;
        }

        Material placedType = placed.getType();
        if (applyDelayTicks > 0L) {
            schedulePlayerTask(player, () -> applyPlacementState(player, placed, placedType, packet), applyDelayTicks);
            return;
        }

        applyPlacementState(player, placed, placedType, packet);
    }

    private void handleUseItemOn(PacketEvent event) {
        if (!runtimeActive) {
            return;
        }

        useItemOnPackets.increment();
        Player player = event.getPlayer();
        if (!easyPlaceV3Enabled || !isEnabledFor(player)) {
            return;
        }

        try {
            PacketContainer packet = event.getPacket();
            MovingObjectPositionBlock hit = packet.getMovingBlockPositions().read(0);
            BlockPosition clickedBlock = hit.getBlockPosition();
            Vector hitVector = hit.getPosVector();
            double relativeX = hitVector.getX() - clickedBlock.getX();

            if (relativeX < 2.0D) {
                debugPlainPacket(player, relativeX);
                return;
            }

            int protocolValue = ((int) Math.floor(relativeX)) - 2;
            if (protocolValue < 0 || protocolValue > maxProtocolValue) {
                debug("Ignoring v3 packet outside configured range: player=" + player.getName()
                        + " protocol=" + protocolValue + " relativeX=" + relativeX);
                return;
            }

            encodedPackets.increment();
            BlockPosition expectedPlacedBlock = offset(clickedBlock, hit.getDirection());
            queuePlacement(player.getUniqueId(),
                    new ServuxPlacementPacket(player.getWorld().getUID(), clickedBlock, expectedPlacedBlock,
                            hit.getDirection(), protocolValue));

            hitVector.setX(clickedBlock.getX() + 0.5D);
            hit.setPosVector(hitVector);
            packet.getMovingBlockPositions().write(0, hit);

            debug("Decoded v3 protocol=" + protocolValue + " player=" + player.getName()
                    + " clicked=" + clickedBlock + " direction=" + hit.getDirection());
        } catch (RuntimeException ex) {
            getLogger().warning("Failed to process USE_ITEM_ON packet for " + player.getName()
                    + ": " + ex.getMessage());
            if (debug) {
                ex.printStackTrace();
            }
        }
    }

    private void sendMetadata(Player player, String reason) {
        if (!runtimeActive || !player.isOnline()) {
            return;
        }

        try {
            byte[] payload = ServuxLitematicsPayload.metadataResponse("PaperServuxCompat-" + getDescription().getVersion());
            player.sendPluginMessage(this, ServuxLitematicsPayload.CHANNEL, payload);
            metadataSent.increment();
            debug("Sent servux:litematics metadata to " + player.getName()
                    + " reason=" + reason + " length=" + payload.length);
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Could not send Servux metadata to " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void applyPlacementState(Player player, Block placed, Material originalType, ServuxPlacementPacket packet) {
        if (!runtimeActive || !player.isOnline()) {
            return;
        }

        if (!placed.getWorld().getUID().equals(player.getWorld().getUID())) {
            debug("Skipping delayed v3 apply because player changed world: player=" + player.getName());
            return;
        }

        if (requireSameMaterialBeforeApply && placed.getType() != originalType) {
            debug("Skipping delayed v3 apply because block type changed: player=" + player.getName()
                    + " original=" + originalType + " current=" + placed.getType()
                    + " at " + placed.getLocation());
            return;
        }

        boolean changed = ServuxV3PlacementApplier.apply(placed, packet.protocolValue(), getLogger(), debug);
        if (changed) {
            appliedPlacements.increment();
        }
        debug("Applied v3 protocol=" + packet.protocolValue() + " changed=" + changed
                + " block=" + placed.getType() + " at " + placed.getLocation());
    }

    private boolean isSamePlacement(ServuxPlacementPacket packet, Block placed, Block against) {
        if (packet.matchesPlacedBlock(placed) || packet.matchesClickedBlock(placed)) {
            return true;
        }

        if (strictPlacementMatch) {
            debug("Strict v3 placement mismatch: expected face=" + packet.clickedFace()
                    + " clicked=" + packet.clickedBlock()
                    + " placed=" + placed.getLocation()
                    + " against=" + against.getLocation());
            return false;
        }

        return packet.matchesClickedBlock(placed)
                || packet.matchesClickedBlock(against)
                || packet.isNearClickedBlock(placed);
    }

    private boolean isEnabledFor(Player player) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(player.getWorld().getName());
    }

    private BlockPosition offset(BlockPosition position, EnumWrappers.Direction direction) {
        return switch (direction) {
            case DOWN -> new BlockPosition(position.getX(), position.getY() - 1, position.getZ());
            case UP -> new BlockPosition(position.getX(), position.getY() + 1, position.getZ());
            case NORTH -> new BlockPosition(position.getX(), position.getY(), position.getZ() - 1);
            case SOUTH -> new BlockPosition(position.getX(), position.getY(), position.getZ() + 1);
            case WEST -> new BlockPosition(position.getX() - 1, position.getY(), position.getZ());
            case EAST -> new BlockPosition(position.getX() + 1, position.getY(), position.getZ());
        };
    }

    boolean isDebugEnabled() {
        return debug;
    }

    void debug(String message) {
        if (debug) {
            getLogger().info("[debug] " + message);
        }
    }

    private void debugPlainPacket(Player player, double relativeX) {
        if (!debug) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastPlainPacketLog.get(player.getUniqueId());
        if (last == null || now - last > 2_000L) {
            lastPlainPacketLog.put(player.getUniqueId(), now);
            debug("Received normal USE_ITEM_ON from " + player.getName()
                    + " relativeX=" + relativeX + " (not Servux v3 encoded)");
        }
    }

    private void queuePlacement(UUID playerId, ServuxPlacementPacket packet) {
        ConcurrentLinkedDeque<ServuxPlacementPacket> queue = pendingPlacements.computeIfAbsent(
                playerId, ignored -> new ConcurrentLinkedDeque<>());
        queue.addLast(packet);

        while (queue.size() > maxPendingPlacementsPerPlayer) {
            ServuxPlacementPacket dropped = queue.pollFirst();
            if (dropped == null) {
                break;
            }
            queueOverflowDrops.increment();
            debug("Dropped oldest queued v3 packet because player queue exceeded "
                    + maxPendingPlacementsPerPlayer + " packets.");
        }
    }

    private ServuxPlacementPacket findMatchingPlacement(UUID playerId, Block placed, Block against) {
        ConcurrentLinkedDeque<ServuxPlacementPacket> queue = pendingPlacements.get(playerId);
        if (queue == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        trimExpiredPlacements(playerId, queue, now);
        for (ServuxPlacementPacket packet : queue) {
            if (isSamePlacement(packet, placed, against) && queue.remove(packet)) {
                removeQueueIfEmpty(playerId, queue);
                return packet;
            }
        }

        removeQueueIfEmpty(playerId, queue);
        return null;
    }

    private void trimExpiredPlacements(UUID playerId, ConcurrentLinkedDeque<ServuxPlacementPacket> queue, long now) {
        while (true) {
            ServuxPlacementPacket oldest = queue.peekFirst();
            if (oldest == null || !oldest.isOlderThanMillis(now, packetMaxAgeMillis)) {
                break;
            }

            if (queue.pollFirst() == null) {
                break;
            }
            expiredPlacements.increment();
        }

        removeQueueIfEmpty(playerId, queue);
    }

    private void removeQueueIfEmpty(UUID playerId, ConcurrentLinkedDeque<ServuxPlacementPacket> queue) {
        if (queue.isEmpty()) {
            pendingPlacements.remove(playerId, queue);
        }
    }

    private long totalPendingPlacements() {
        long total = 0L;
        for (ConcurrentLinkedDeque<ServuxPlacementPacket> queue : pendingPlacements.values()) {
            total += queue.size();
        }
        return total;
    }

    private void schedulePlayerTask(Player player, Runnable action, long delayTicks) {
        UUID playerId = player.getUniqueId();
        AtomicReference<ScheduledTask> taskReference = new AtomicReference<>();
        Runnable retired = () -> untrackScheduledTask(playerId, taskReference.get());
        ScheduledTask scheduledTask = player.getScheduler().runDelayed(this, task -> {
            untrackScheduledTask(playerId, task);
            if (!runtimeActive || !player.isOnline()) {
                return;
            }
            action.run();
        }, retired, Math.max(1L, delayTicks));

        taskReference.set(scheduledTask);
        trackScheduledTask(playerId, scheduledTask);
        if (scheduledTask.isCancelled()) {
            untrackScheduledTask(playerId, scheduledTask);
        }
    }

    private void trackScheduledTask(UUID playerId, ScheduledTask task) {
        if (task == null) {
            return;
        }

        scheduledTasks.add(task);
        scheduledTasksByPlayer.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(task);
    }

    private void untrackScheduledTask(UUID playerId, ScheduledTask task) {
        if (task == null) {
            return;
        }

        scheduledTasks.remove(task);
        Set<ScheduledTask> playerTasks = scheduledTasksByPlayer.get(playerId);
        if (playerTasks != null) {
            playerTasks.remove(task);
            if (playerTasks.isEmpty()) {
                scheduledTasksByPlayer.remove(playerId, playerTasks);
            }
        }
    }

    private void cancelPlayerTasks(UUID playerId) {
        Set<ScheduledTask> playerTasks = scheduledTasksByPlayer.remove(playerId);
        if (playerTasks == null) {
            return;
        }

        for (ScheduledTask task : Set.copyOf(playerTasks)) {
            scheduledTasks.remove(task);
            cancelScheduledTask(task);
        }
        playerTasks.clear();
    }

    private void cancelTrackedTasks() {
        for (ScheduledTask task : Set.copyOf(scheduledTasks)) {
            cancelScheduledTask(task);
        }
        scheduledTasks.clear();
        scheduledTasksByPlayer.clear();

        try {
            getServer().getScheduler().cancelTasks(this);
        } catch (RuntimeException ex) {
            debug("Could not cancel legacy scheduler tasks during unload: " + ex.getMessage());
        }
    }

    private void cancelScheduledTask(ScheduledTask task) {
        try {
            task.cancel();
        } catch (RuntimeException ex) {
            debug("Could not cancel scheduled task during unload: " + ex.getMessage());
        }
    }

    private boolean isPlugManPresent() {
        return getServer().getPluginManager().getPlugin("PlugMan") != null
                || getServer().getPluginManager().getPlugin("PlugManX") != null;
    }

    private void restartMaintenanceExecutor() {
        stopMaintenanceExecutor();
        if (!asyncMaintenanceEnabled) {
            return;
        }

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "PaperServuxCompat-maintenance");
            thread.setDaemon(true);
            return thread;
        };
        maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(factory);
        maintenanceExecutor.scheduleWithFixedDelay(this::runAsyncMaintenance,
                asyncMaintenanceIntervalMillis,
                asyncMaintenanceIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    private void stopMaintenanceExecutor() {
        ScheduledExecutorService executor = maintenanceExecutor;
        maintenanceExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void runAsyncMaintenance() {
        try {
            long now = System.currentTimeMillis();
            pendingPlacements.forEach((playerId, queue) -> trimExpiredPlacements(playerId, queue, now));

            long debugCutoff = now - Math.max(10_000L, packetMaxAgeMillis * 4L);
            lastPlainPacketLog.entrySet().removeIf(entry -> entry.getValue() < debugCutoff);
        } catch (RuntimeException ex) {
            debug("Async maintenance failed: " + ex.getMessage());
        }
    }
}
