package com.convallyria.hugestructureblocks.utils;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.PalettedContainer;

import java.util.List;

public interface IChunkStorageWriter extends AutoCloseable {
    void writeSection(int cx, int sy, int cz, PalettedContainer<BlockState> container);
    void writeBlockEntities(List<NbtCompound> blockEntities);
    void flushAndClose() throws Exception;
}
