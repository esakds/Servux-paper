package com.codexmc.servuxcompat;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.block.Block;

import java.util.UUID;

final class ServuxPlacementPacket {
    private final UUID worldId;
    private final BlockPosition clickedBlock;
    private final BlockPosition expectedPlacedBlock;
    private final EnumWrappers.Direction clickedFace;
    private final int protocolValue;
    private final long createdAtMillis;

    ServuxPlacementPacket(UUID worldId, BlockPosition clickedBlock, BlockPosition expectedPlacedBlock,
                          EnumWrappers.Direction clickedFace, int protocolValue) {
        this.worldId = worldId;
        this.clickedBlock = clickedBlock;
        this.expectedPlacedBlock = expectedPlacedBlock;
        this.clickedFace = clickedFace;
        this.protocolValue = protocolValue;
        this.createdAtMillis = System.currentTimeMillis();
    }

    BlockPosition clickedBlock() {
        return clickedBlock;
    }

    EnumWrappers.Direction clickedFace() {
        return clickedFace;
    }

    int protocolValue() {
        return protocolValue;
    }

    boolean isOlderThanMillis(long maxAgeMillis) {
        return isOlderThanMillis(System.currentTimeMillis(), maxAgeMillis);
    }

    boolean isOlderThanMillis(long nowMillis, long maxAgeMillis) {
        return nowMillis - createdAtMillis > maxAgeMillis;
    }

    boolean matchesPlacedBlock(Block block) {
        return isSameWorld(block) && sameBlock(expectedPlacedBlock, block);
    }

    boolean matchesClickedBlock(Block block) {
        return isSameWorld(block) && sameBlock(clickedBlock, block);
    }

    boolean isNearClickedBlock(Block block) {
        return isSameWorld(block)
                && Math.abs(block.getX() - clickedBlock.getX())
                + Math.abs(block.getY() - clickedBlock.getY())
                + Math.abs(block.getZ() - clickedBlock.getZ()) <= 1;
    }

    private boolean isSameWorld(Block block) {
        return block.getWorld().getUID().equals(worldId);
    }

    private static boolean sameBlock(BlockPosition position, Block block) {
        return position.getX() == block.getX()
                && position.getY() == block.getY()
                && position.getZ() == block.getZ();
    }
}
