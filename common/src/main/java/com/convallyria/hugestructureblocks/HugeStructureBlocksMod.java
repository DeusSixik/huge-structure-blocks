package com.convallyria.hugestructureblocks;

import com.convallyria.hugestructureblocks.config.BSConfig;
import com.convallyria.hugestructureblocks.utils.StructureWandClient;
import com.convallyria.hugestructureblocks.utils.StructureWandTool;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public final class HugeStructureBlocksMod {

    public static final String MOD_ID = "hugestructureblocks";

    public static final Item WAND = Items.SHEARS;

    // New size for structures, TODO: Configurable?
    public static final int NEW_STRUCTURE_SIZE = 16384;

    public static final Logger LOGGER = LogManager.getLogger();

    public static Runnable CLEAR_ZONE = () -> {};

    public static Path CONFIG_PATH;

    public static void init() {
        LOGGER.info("Huge Structure Blocks is now making your structure blocks even bigger!");
        LOGGER.info("New structure size = " + NEW_STRUCTURE_SIZE);

        StructureWandTool.register();

        if(Platform.getEnv() == EnvType.CLIENT)
            StructureWandClient.register();

        BSConfig.createConfigDir(CONFIG_PATH);
    }
}
