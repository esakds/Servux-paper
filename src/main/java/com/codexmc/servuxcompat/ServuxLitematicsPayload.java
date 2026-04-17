package com.codexmc.servuxcompat;

import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

final class ServuxLitematicsPayload {
    static final String CHANNEL = "servux:litematics";
    static final int PACKET_S2C_METADATA = 1;
    static final int PACKET_C2S_METADATA_REQUEST = 2;
    static final int PACKET_C2S_BLOCK_ENTITY_REQUEST = 3;
    static final int PACKET_C2S_ENTITY_REQUEST = 4;
    static final int PACKET_C2S_BULK_ENTITY_NBT_REQUEST = 7;
    static final int PACKET_C2S_NBT_RESPONSE_DATA = 13;

    private ServuxLitematicsPayload() {
    }

    static byte[] metadataResponse(String versionString) throws IOException {
        ByteArrayOutputStream rawData = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(rawData);

        StreamSerializer.getDefault().serializeVarInt(output, PACKET_S2C_METADATA);
        NbtCompound metadata = NbtFactory.ofCompound("metadata", List.of(
                NbtFactory.of("name", "litematic_data"),
                NbtFactory.of("id", CHANNEL),
                NbtFactory.of("version", 1),
                NbtFactory.of("servux", versionString)));
        StreamSerializer.getDefault().serializeCompound(output, metadata);
        output.flush();

        return rawData.toByteArray();
    }

    static int readPacketType(byte[] payload) {
        int value = 0;
        int shift = 0;

        for (byte next : payload) {
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }

            shift += 7;
            if (shift > 35) {
                throw new IllegalArgumentException("VarInt is too large");
            }
        }

        throw new IllegalArgumentException("Missing VarInt terminator");
    }
}
