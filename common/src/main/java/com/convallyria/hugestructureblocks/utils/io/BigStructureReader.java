package com.convallyria.hugestructureblocks.utils.io;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.PalettedContainer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public class BigStructureReader implements AutoCloseable {

    public final BlockPos origin;
    private final DataInputStream in;
    private final PacketByteBuf buffer;

    public BigStructureReader(Path filePath) throws IOException {
        this.in = new DataInputStream(
                new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(filePath), 65536)
                )
        );
        this.buffer = new PacketByteBuf(Unpooled.buffer());

        int ox = in.readInt();
        int oy = in.readInt();
        int oz = in.readInt();
        this.origin = new BlockPos(ox, oy, oz);
    }

    public SectionData readNextSection() throws IOException {
        try {
            int cx = in.readInt();
            int sy = in.readInt();
            int cz = in.readInt();

            int minX = in.readUnsignedByte();
            int minY = in.readUnsignedByte();
            int minZ = in.readUnsignedByte();
            int maxX = in.readUnsignedByte();
            int maxY = in.readUnsignedByte();
            int maxZ = in.readUnsignedByte();

            int length = in.readInt();

            if (length < 0 || length > 1_048_576) {
                throw new IOException("Desync detected! Corrupted length: " + length);
            }

            byte[] chunkData = new byte[length];
            in.readFully(chunkData);

            buffer.clear();
            buffer.writeBytes(chunkData);

            PalettedContainer<BlockState> container = new PalettedContainer<>(
                    Block.STATE_IDS,
                    Blocks.STRUCTURE_VOID.getDefaultState(),
                    PalettedContainer.PaletteProvider.BLOCK_STATE
            );
            container.readPacket(buffer);

            return new SectionData(cx, sy, cz, minX, minY, minZ, maxX, maxY, maxZ, container);

        } catch (EOFException e) {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        in.close();
        buffer.release();
    }

    public record SectionData(int cx, int sy, int cz,
                              int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ,
                              PalettedContainer<BlockState> container) {}
}
