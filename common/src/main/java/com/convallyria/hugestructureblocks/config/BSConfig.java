package com.convallyria.hugestructureblocks.config;

import java.io.File;
import java.nio.file.Path;

public class BSConfig {

    public static Path MAIN_FOLDER;
    public static Path STRUCTURES_FOLDER;

    public static void createConfigDir(Path path) {
        MAIN_FOLDER = path.resolve("bts_structures");

        File file = MAIN_FOLDER.toFile();
        if(!file.exists())
            file.mkdir();

        STRUCTURES_FOLDER = MAIN_FOLDER.resolve("structures");
        file = STRUCTURES_FOLDER.toFile();

        if(!file.exists())
            file.mkdir();
    }
}
