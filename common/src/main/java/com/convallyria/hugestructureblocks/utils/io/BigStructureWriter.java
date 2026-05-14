package com.convallyria.hugestructureblocks.utils.io;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class BigStructureWriter implements AutoCloseable {

    static final int MAGIC = 0x48534232; // HSB2
    static final int VERSION_STABLE_PALETTE = 2;
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int MAX_SECTION_PALETTE_SIZE = SECTION_VOLUME + 1;

    public final BlockPos origin;
    public final Path filePath;
    protected @Nullable DataOutputStream out;
    // Stores per-section palette indexes, not global/raw block state IDs.
    // A 16x16x16 section can reference at most 4096 states plus STRUCTURE_VOID.
    private final char[] sectionBlockStateIds = new char[SECTION_VOLUME];
    private final long[] sectionPackedStates = new long[SECTION_VOLUME];
    private final List<BlockState> sectionPalette = new ArrayList<>(64);
    private final Reference2IntOpenHashMap<BlockState> sectionPaletteIds = new Reference2IntOpenHashMap<>(64);
    private final List<BEData> blockEntityBuffer = new ArrayList<>();
    private final Reference2ObjectOpenHashMap<BlockState, String> encodedStateCache = new Reference2ObjectOpenHashMap<>();

    public BigStructureWriter(Path filePath, BlockPos origin) {
        this.origin = origin;
        this.filePath = filePath;
        this.sectionPaletteIds.defaultReturnValue(-1);
    }

    protected DataOutputStream createStream(Path filePath) throws IOException {
        return new DataOutputStream(
                new BufferedOutputStream(
                        new GZIPOutputStream(Files.newOutputStream(filePath), 65536)
                )
        );
    }

    public void startWrite(int cx, int sy, int cz, WorldChunk chunk, BlockBox structureBox) throws IOException {
        if (this.out == null) {
            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            this.out = createStream(filePath);

            this.out.writeInt(MAGIC);
            this.out.writeInt(VERSION_STABLE_PALETTE);

            this.out.writeInt(origin.getX());
            this.out.writeInt(origin.getY());
            this.out.writeInt(origin.getZ());

            this.out.writeInt(structureBox.getBlockCountX());
            this.out.writeInt(structureBox.getBlockCountY());
            this.out.writeInt(structureBox.getBlockCountZ());
        }
        writeSection(cx, sy, cz, chunk, structureBox);
    }

    protected void writeSection(int cx, int sy, int cz, WorldChunk chunk, BlockBox structureBox) throws IOException {
        ChunkSection[] sections = chunk.getSectionArray();
        int sectionIndex = chunk.sectionCoordToIndex(sy);

        if (sectionIndex < 0 || sectionIndex >= sections.length) return;
        ChunkSection section = sections[sectionIndex];
        if (section == null || section.isEmpty()) return;

        int minX = Math.max(0, structureBox.getMinX() - (cx << 4));
        int maxX = Math.min(15, structureBox.getMaxX() - (cx << 4));
        int minY = Math.max(0, structureBox.getMinY() - (sy << 4));
        int maxY = Math.min(15, structureBox.getMaxY() - (sy << 4));
        int minZ = Math.max(0, structureBox.getMinZ() - (cz << 4));
        int maxZ = Math.min(15, structureBox.getMaxZ() - (cz << 4));

        if (minX > maxX || minY > maxY || minZ > maxZ) return;

        PalettedContainer<BlockState> source = section.getBlockStateContainer();
        boolean hasData = false;

        Arrays.fill(sectionBlockStateIds, (char) 0);
        sectionPalette.clear();
        sectionPaletteIds.clear();
        blockEntityBuffer.clear();

        BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();
        sectionPalette.add(voidState);
        sectionPaletteIds.put(voidState, 0);

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState state = source.get(x, y, z);
                    if (!state.isAir()) {
                        sectionBlockStateIds[sectionIndex(x, y, z)] = (char) getPaletteId(state, sectionPalette, sectionPaletteIds);
                        hasData = true;

                        if (state.hasBlockEntity()) {
                            BlockPos globalPos = new BlockPos((cx << 4) + x, (sy << 4) + y, (cz << 4) + z);
                            net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(globalPos, WorldChunk.CreationType.IMMEDIATE);
                            if (be != null) {
                                NbtCompound nbt = be.createNbtWithId(chunk.getWorld().getRegistryManager());
                                if (nbt != null) {
                                    blockEntityBuffer.add(new BEData(x, y, z, nbt));
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!hasData) return;

        out.writeByte(3);

        out.writeInt(cx); out.writeInt(sy); out.writeInt(cz);
        out.writeByte(minX); out.writeByte(minY); out.writeByte(minZ);
        out.writeByte(maxX); out.writeByte(maxY); out.writeByte(maxZ);

        out.writeInt(sectionPalette.size());
        for (BlockState state : sectionPalette) {
            out.writeUTF(encodedStateCache.computeIfAbsent(state, StableBlockStateCodec::encode));
        }

        int bits = bitsNeeded(sectionPalette.size() - 1);
        int packedLength = packBlockStateIds(sectionBlockStateIds, bits, sectionPackedStates);
        out.writeByte(bits);
        out.writeInt(packedLength);
        for (int i = 0; i < packedLength; i++) {
            out.writeLong(sectionPackedStates[i]);
        }

        out.writeInt(blockEntityBuffer.size());
        for (BEData be : blockEntityBuffer) {
            out.writeByte(be.lx);
            out.writeByte(be.ly);
            out.writeByte(be.lz);
            net.minecraft.nbt.NbtIo.write(be.nbt, (java.io.DataOutput) out);
        }
    }

    public void writeEntity(net.minecraft.entity.Entity entity) throws IOException {
        if (this.out == null) return;
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        if (entity.saveNbt(nbt)) {
            out.writeByte(2);
            net.minecraft.util.math.Vec3d vec = entity.getPos().subtract(origin.getX(), origin.getY(), origin.getZ());
            out.writeDouble(vec.x);
            out.writeDouble(vec.y);
            out.writeDouble(vec.z);
            net.minecraft.nbt.NbtIo.write(nbt, (java.io.DataOutput) out);
        }
    }

    @Override
    public void close() throws Exception {
        if (out != null) {
            out.flush();
            out.close();
        }
    }

    private static int getPaletteId(BlockState state, List<BlockState> palette, Reference2IntOpenHashMap<BlockState> paletteIds) {
        int id = paletteIds.getInt(state);
        if (id != -1) {
            return id;
        }

        int newId = palette.size();
        if (newId >= MAX_SECTION_PALETTE_SIZE || newId > Character.MAX_VALUE) {
            throw new IllegalStateException("Section palette is too large: " + (newId + 1));
        }
        palette.add(state);
        paletteIds.put(state, newId);
        return newId;
    }

    private static int packBlockStateIds(char[] blockStateIds, int bits, long[] packed) {
        if (bits == 0) {
            return 0;
        }

        int valuesPerLong = Long.SIZE / bits;
        long mask = (1L << bits) - 1L;
        int packedLength = (blockStateIds.length + valuesPerLong - 1) / valuesPerLong;
        Arrays.fill(packed, 0, packedLength, 0L);
        for (int i = 0; i < blockStateIds.length; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            packed[longIndex] |= ((long) blockStateIds[i] & mask) << bitOffset;
        }
        return packedLength;
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue == 0 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    static int sectionIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private record BEData(int lx, int ly, int lz, NbtCompound nbt) {}
}
