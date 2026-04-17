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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PaperServuxCompatPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private final ConcurrentHashMap<UUID, ServuxPlacementPacket> pendingPlacements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastPlainPacketLog = new ConcurrentHashMap<>();
    private final AtomicLong metadataRequests = new AtomicLong();
    private final AtomicLong metadataSent = new AtomicLong();
    private final AtomicLong useItemOnPackets = new AtomicLong();
    private final AtomicLong encodedPackets = new AtomicLong();
    private final AtomicLong blockPlaceEvents = new AtomicLong();
    private final AtomicLong appliedPlacements = new AtomicLong();

    private ProtocolManager protocolManager;
    private boolean debug;
    private Set<String> enabledWorlds;
    private boolean metadataEnabled;
    private int joinMetadataDelayTicks;
    private boolean easyPlaceV3Enabled;
    private long packetMaxAgeMillis;
    private int maxProtocolValue;
    private boolean strictPlacementMatch;
    private long applyDelayTicks;
    private boolean requireSameMaterialBeforeApply;
    private boolean applyBlockState;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        getServer().getMessenger().registerOutgoingPluginChannel(this, ServuxLitematicsPayload.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, ServuxLitematicsPayload.CHANNEL, this);

        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.LOWEST,
                PacketType.Play.Client.USE_ITEM_ON) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleUseItemOn(event);
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PaperServuxCompat enabled for servux:litematics metadata and Litematica Easy Place v3.");
    }

    @Override
    public void onDisable() {
        pendingPlacements.clear();
        lastPlainPacketLog.clear();
        getLogger().info("Stats: metadata_requests=" + metadataRequests.get()
                + ", metadata_sent=" + metadataSent.get()
                + ", use_item_on=" + useItemOnPackets.get()
                + ", encoded_v3=" + encodedPackets.get()
                + ", block_place=" + blockPlaceEvents.get()
                + ", applied=" + appliedPlacements.get());

        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }

        getServer().getMessenger().unregisterIncomingPluginChannel(this, ServuxLitematicsPayload.CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, ServuxLitematicsPayload.CHANNEL);
    }

    private void reloadLocalConfig() {
        debug = getConfig().getBoolean("debug", false);
        enabledWorlds = Set.copyOf(getConfig().getStringList("enabled-worlds"));
        metadataEnabled = getConfig().getBoolean("metadata-enabled", true);
        joinMetadataDelayTicks = Math.max(1, getConfig().getInt("join-metadata-delay-ticks", 40));
        easyPlaceV3Enabled = getConfig().getBoolean("easy-place-v3-enabled", true);
        packetMaxAgeMillis = Math.max(100L, getConfig().getLong("packet-max-age-millis", 1_200L));
        maxProtocolValue = Math.max(15, getConfig().getInt("max-protocol-value", 1_048_575));
        strictPlacementMatch = getConfig().getBoolean("strict-placement-match", true);
        applyDelayTicks = Math.max(0L, getConfig().getLong("apply-delay-ticks", 1L));
        requireSameMaterialBeforeApply = getConfig().getBoolean("require-same-material-before-apply", true);
        applyBlockState = getConfig().getBoolean("apply-block-state", true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!metadataEnabled || !isEnabledFor(player)) {
            return;
        }

        player.getScheduler().runDelayed(this, task -> sendMetadata(player, "join"),
                null, joinMetadataDelayTicks);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingPlacements.remove(event.getPlayer().getUniqueId());
        lastPlainPacketLog.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ServuxLitematicsPayload.CHANNEL.equals(channel) || !metadataEnabled || !isEnabledFor(player)) {
            return;
        }

        try {
            int packetType = ServuxLitematicsPayload.readPacketType(message);
            if (packetType == ServuxLitematicsPayload.PACKET_C2S_METADATA_REQUEST) {
                metadataRequests.incrementAndGet();
                sendMetadata(player, "metadata request");
            } else if (packetType == ServuxLitematicsPayload.PACKET_C2S_BLOCK_ENTITY_REQUEST
                    || packetType == ServuxLitematicsPayload.PACKET_C2S_ENTITY_REQUEST
                    || packetType == ServuxLitematicsPayload.PACKET_C2S_BULK_ENTITY_NBT_REQUEST
                    || packetType == ServuxLitematicsPayload.PACKET_C2S_NBT_RESPONSE_DATA) {
                debug("Received unsupported servux:litematics packet type=" + packetType
                        + " from " + player.getName() + " length=" + message.length);
            } else {
                debug("Received unknown servux:litematics packet type=" + packetType
                        + " from " + player.getName() + " length=" + message.length);
            }
        } catch (RuntimeException ex) {
            debug("Could not read servux:litematics payload from " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        blockPlaceEvents.incrementAndGet();
        Player player = event.getPlayer();
        if (!easyPlaceV3Enabled || !applyBlockState || !isEnabledFor(player)) {
            return;
        }

        ServuxPlacementPacket packet = pendingPlacements.remove(player.getUniqueId());
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
            player.getScheduler().runDelayed(this,
                    task -> applyPlacementState(player, placed, placedType, packet),
                    null, applyDelayTicks);
            return;
        }

        applyPlacementState(player, placed, placedType, packet);
    }

    private void handleUseItemOn(PacketEvent event) {
        useItemOnPackets.incrementAndGet();
        Player player = event.getPlayer();
        if (!easyPlaceV3Enabled || !isEnabledFor(player)) {
            pendingPlacements.remove(player.getUniqueId());
            return;
        }

        try {
            PacketContainer packet = event.getPacket();
            MovingObjectPositionBlock hit = packet.getMovingBlockPositions().read(0);
            BlockPosition clickedBlock = hit.getBlockPosition();
            Vector hitVector = hit.getPosVector();
            double relativeX = hitVector.getX() - clickedBlock.getX();

            if (relativeX < 2.0D) {
                pendingPlacements.remove(player.getUniqueId());
                debugPlainPacket(player, relativeX);
                return;
            }

            int protocolValue = ((int) Math.floor(relativeX)) - 2;
            if (protocolValue < 0 || protocolValue > maxProtocolValue) {
                pendingPlacements.remove(player.getUniqueId());
                debug("Ignoring v3 packet outside configured range: player=" + player.getName()
                        + " protocol=" + protocolValue + " relativeX=" + relativeX);
                return;
            }

            encodedPackets.incrementAndGet();
            BlockPosition expectedPlacedBlock = offset(clickedBlock, hit.getDirection());
            pendingPlacements.put(player.getUniqueId(),
                    new ServuxPlacementPacket(player.getWorld().getUID(), clickedBlock, expectedPlacedBlock,
                            hit.getDirection(), protocolValue));

            hitVector.setX(clickedBlock.getX() + 0.5D);
            hit.setPosVector(hitVector);
            packet.getMovingBlockPositions().write(0, hit);

            debug("Decoded v3 protocol=" + protocolValue + " player=" + player.getName()
                    + " clicked=" + clickedBlock + " direction=" + hit.getDirection());
        } catch (RuntimeException ex) {
            pendingPlacements.remove(player.getUniqueId());
            getLogger().warning("Failed to process USE_ITEM_ON packet for " + player.getName()
                    + ": " + ex.getMessage());
            if (debug) {
                ex.printStackTrace();
            }
        }
    }

    private void sendMetadata(Player player, String reason) {
        try {
            byte[] payload = ServuxLitematicsPayload.metadataResponse("PaperServuxCompat-" + getDescription().getVersion());
            player.sendPluginMessage(this, ServuxLitematicsPayload.CHANNEL, payload);
            metadataSent.incrementAndGet();
            debug("Sent servux:litematics metadata to " + player.getName()
                    + " reason=" + reason + " length=" + payload.length);
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Could not send Servux metadata to " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void applyPlacementState(Player player, Block placed, Material originalType, ServuxPlacementPacket packet) {
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
            appliedPlacements.incrementAndGet();
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

    private void debug(String message) {
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
}
