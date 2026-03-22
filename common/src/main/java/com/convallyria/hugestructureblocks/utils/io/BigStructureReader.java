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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class BigStructureReader implements AutoCloseable {

    public final BlockPos origin;
    private final DataInputStream in;
    private final PacketByteBuf buffer;

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

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

        this.sizeX = in.readInt();
        this.sizeY = in.readInt();
        this.sizeZ = in.readInt();
    }

    public Object readNextRecord() throws IOException {
        try {
            int type = in.readByte();

            if (type == 1) { // СЕКЦИЯ С БЛОКАМИ И МЕХАНИЗМАМИ
                int cx = in.readInt(); int sy = in.readInt(); int cz = in.readInt();
                int minX = in.readUnsignedByte(); int minY = in.readUnsignedByte(); int minZ = in.readUnsignedByte();
                int maxX = in.readUnsignedByte(); int maxY = in.readUnsignedByte(); int maxZ = in.readUnsignedByte();

                int length = in.readInt();
                if (length < 0 || length > 1_048_576) throw new IOException("Desync detected! Corrupted length: " + length);

                byte[] chunkData = new byte[length];
                in.readFully(chunkData);
                buffer.clear();
                buffer.writeBytes(chunkData);

                PalettedContainer<BlockState> container = new PalettedContainer<>(
                        Block.STATE_IDS, Blocks.STRUCTURE_VOID.getDefaultState(), PalettedContainer.PaletteProvider.BLOCK_STATE
                );
                container.readPacket(buffer);

                // Чтение BlockEntity
                int beCount = in.readInt();
                List<BlockEntityData> blockEntities = new ArrayList<>();
                for (int i = 0; i < beCount; i++) {
                    int lx = in.readUnsignedByte(); int ly = in.readUnsignedByte(); int lz = in.readUnsignedByte();
                    net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompound(in, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                    blockEntities.add(new BlockEntityData(lx, ly, lz, nbt));
                }

                return new SectionData(cx, sy, cz, minX, minY, minZ, maxX, maxY, maxZ, container, blockEntities);

            } else if (type == 2) { // ENTITY
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompound(in, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                return new EntityData(x, y, z, nbt);
            }
            return null; // EOF или неизвестный тип

        } catch (EOFException e) {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        in.close();
        buffer.release();
    }

    public record SectionData(int cx, int sy, int cz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, PalettedContainer<BlockState> container, List<BlockEntityData> blockEntities) {}
    public record BlockEntityData(int lx, int ly, int lz, net.minecraft.nbt.NbtCompound nbt) {}
    public record EntityData(double x, double y, double z, net.minecraft.nbt.NbtCompound nbt) {}
}
