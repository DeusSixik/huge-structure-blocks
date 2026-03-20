package com.convallyria.hugestructureblocks.utils;

import com.convallyria.hugestructureblocks.utils.io.BigStructureWriter;
import com.convallyria.hugestructureblocks.utils.io.StructureSaveTask;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class BTSStructureTemplate extends StructureTemplate {
    private final BigStructureWriter writer;

    public BTSStructureTemplate(BigStructureWriter writer) {
        this.writer = writer;
    }

    @Override
    public void saveFromWorld(World world, BlockPos start, Vec3i dimensions, boolean includeEntities, @Nullable Block ignoredBlock) {
        if (dimensions.getX() < 1 || dimensions.getY() < 1 || dimensions.getZ() < 1 || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockPos end = start.add(dimensions).add(-1, -1, -1);

        // Запускаем State Machine
        new StructureSaveTask(serverWorld, writer, start, end).start();
    }
}
