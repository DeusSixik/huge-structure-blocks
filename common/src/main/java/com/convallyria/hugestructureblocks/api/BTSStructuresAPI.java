package com.convallyria.hugestructureblocks.api;

import com.convallyria.hugestructureblocks.api.exceptions.StructureGenerationException;
import com.convallyria.hugestructureblocks.api.exceptions.StructureNotFoundException;
import com.convallyria.hugestructureblocks.config.BSConfig;
import com.convallyria.hugestructureblocks.utils.io.BigStructureReader;
import com.convallyria.hugestructureblocks.utils.io.StructureLoadTask;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BTSStructuresAPI {

    public static void loadStructure(ServerWorld world, BlockPos position, String structureName) throws StructureGenerationException, IOException {
        Path filePath = BSConfig.STRUCTURES_FOLDER.resolve(structureName + ".bin");

        if (!Files.exists(filePath)) {
            throw new StructureNotFoundException(structureName);
        }

        BigStructureReader reader = new BigStructureReader(filePath);
        int offsetX = position.getX() - reader.origin.getX();
        int offsetY = position.getY() - reader.origin.getY();
        int offsetZ = position.getZ() - reader.origin.getZ();

        new StructureLoadTask(world, reader, offsetX, offsetY, offsetZ).start();
    }
}
