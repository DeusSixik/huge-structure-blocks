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

    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int MAX_PALETTE_SIZE = SECTION_VOLUME + 1;

    public final BlockPos origin;
    private final DataInputStream in;
    private final PacketByteBuf buffer;
    private final int formatVersion;

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

        int first = in.readInt();
        int ox;
        if (first == BigStructureWriter.MAGIC) {
            this.formatVersion = in.readInt();
            if (this.formatVersion != BigStructureWriter.VERSION_STABLE_PALETTE) {
                throw new IOException("Unsupported big structure format version: " + this.formatVersion);
            }
            ox = in.readInt();
        } else {
            this.formatVersion = 1;
            ox = first;
        }

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

            if (type == 1) {
                return readLegacySection();
            } else if (type == 3) {
                return readStableSection();
            } else if (type == 2) {
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompound(in, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                return new EntityData(x, y, z, nbt);
            }
            return null;

        } catch (EOFException e) {
            return null;
        }
    }

    private SectionData readLegacySection() throws IOException {
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

        List<BlockEntityData> blockEntities = readBlockEntities();
        return new SectionData(cx, sy, cz, minX, minY, minZ, maxX, maxY, maxZ, container, null, null, blockEntities);
    }

    private SectionData readStableSection() throws IOException {
        int cx = in.readInt(); int sy = in.readInt(); int cz = in.readInt();
        int minX = in.readUnsignedByte(); int minY = in.readUnsignedByte(); int minZ = in.readUnsignedByte();
        int maxX = in.readUnsignedByte(); int maxY = in.readUnsignedByte(); int maxZ = in.readUnsignedByte();

        int paletteSize = in.readInt();
        if (paletteSize <= 0 || paletteSize > MAX_PALETTE_SIZE) {
            throw new IOException("Desync detected! Corrupted palette size: " + paletteSize);
        }

        BlockState[] palette = new BlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = StableBlockStateCodec.decode(in.readUTF());
        }

        int bits = in.readUnsignedByte();
        int expectedBits = bitsNeeded(paletteSize - 1);
        if (bits != expectedBits) {
            throw new IOException("Desync detected! Corrupted bits per block: " + bits);
        }

        int packedLength = in.readInt();
        int expectedLength = packedLength(bits);
        if (packedLength != expectedLength) {
            throw new IOException("Desync detected! Corrupted packed length: " + packedLength);
        }

        long[] packedStates = new long[packedLength];
        for (int i = 0; i < packedStates.length; i++) {
            packedStates[i] = in.readLong();
        }

        int[] blockStateIds = unpackBlockStateIds(packedStates, bits, paletteSize);
        List<BlockEntityData> blockEntities = readBlockEntities();
        return new SectionData(cx, sy, cz, minX, minY, minZ, maxX, maxY, maxZ, null, palette, blockStateIds, blockEntities);
    }

    private List<BlockEntityData> readBlockEntities() throws IOException {
        int beCount = in.readInt();
        List<BlockEntityData> blockEntities = new ArrayList<>();
        for (int i = 0; i < beCount; i++) {
            int lx = in.readUnsignedByte(); int ly = in.readUnsignedByte(); int lz = in.readUnsignedByte();
            net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompound(in, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            blockEntities.add(new BlockEntityData(lx, ly, lz, nbt));
        }
        return blockEntities;
    }

    @Override
    public void close() throws Exception {
        in.close();
        buffer.release();
    }

    private static int[] unpackBlockStateIds(long[] packedStates, int bits, int paletteSize) throws IOException {
        int[] blockStateIds = new int[SECTION_VOLUME];
        if (bits == 0) {
            return blockStateIds;
        }

        int valuesPerLong = Long.SIZE / bits;
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < blockStateIds.length; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            int paletteId = (int) ((packedStates[longIndex] >>> bitOffset) & mask);
            if (paletteId < 0 || paletteId >= paletteSize) {
                throw new IOException("Desync detected! Palette id out of bounds: " + paletteId);
            }
            blockStateIds[i] = paletteId;
        }
        return blockStateIds;
    }

    private static int packedLength(int bits) {
        if (bits == 0) {
            return 0;
        }
        int valuesPerLong = Long.SIZE / bits;
        return (SECTION_VOLUME + valuesPerLong - 1) / valuesPerLong;
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue == 0 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public record SectionData(int cx, int sy, int cz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              PalettedContainer<BlockState> container, BlockState[] palette, int[] blockStateIds,
                              List<BlockEntityData> blockEntities) {
        public BlockState getState(int x, int y, int z) {
            if (palette != null && blockStateIds != null) {
                return palette[blockStateIds[BigStructureWriter.sectionIndex(x, y, z)]];
            }
            return container.get(x, y, z);
        }
    }

    public record BlockEntityData(int lx, int ly, int lz, net.minecraft.nbt.NbtCompound nbt) {}
    public record EntityData(double x, double y, double z, net.minecraft.nbt.NbtCompound nbt) {}
}
