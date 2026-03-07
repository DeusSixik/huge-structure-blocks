package com.convallyria.hugestructureblocks.utils;

import com.convallyria.hugestructureblocks.utils.io.BigStructureWriter;
import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BTSStructureTemplate extends StructureTemplate {

    protected static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNKS_PER_TICK = 25; // Ограничение батча

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
        ChunkPos startChunk = new ChunkPos(start);
        ChunkPos endChunk = new ChunkPos(end);
        BlockBox structureBox = BlockBox.create(start, end);

        serverWorld.getPlayers().forEach(s -> s.sendMessage(Text.of("Start saving structure...")));

        // Создаем очередь всех чанков (State Machine)
        Queue<ChunkPos> queue = new ConcurrentLinkedQueue<>();
        for (int cx = startChunk.x; cx <= endChunk.x; cx++) {
            for (int cz = startChunk.z; cz <= endChunk.z; cz++) {
                queue.add(new ChunkPos(cx, cz));
            }
        }

        // Запускаем асинхронную обработку батчами
        processBatch(serverWorld, queue, start, end, structureBox);
    }

    private void processBatch(ServerWorld world, Queue<ChunkPos> queue, BlockPos start, BlockPos end, BlockBox box) {
        if (queue.isEmpty()) {
            finalizeSaving(world);
            return;
        }

        // Переводим выполнение в Главный Поток (Tick Loop) для безопасной работы с чанками
        world.getServer().execute(() -> {
            int processed = 0;

            // HOT PATH: Обрабатываем строго лимитированную пачку чанков
            while (!queue.isEmpty() && processed < CHUNKS_PER_TICK) {
                ChunkPos pos = queue.poll();

                // Синхронно грузим чанк. Если его нет в ОЗУ — он загрузится с диска
                WorldChunk chunk = world.getChunk(pos.x, pos.z);

                if (chunk != null && !chunk.isEmpty()) {
                    processChunk(world, pos.x, pos.z, start, end, box, chunk);
                }

                processed++;
            }

            // Возвращаем задачу в ForkJoinPool и даем серверу протикать
            CompletableFuture.runAsync(() -> {
                try {
                    // Пауза 50мс (1 тик), чтобы сервер успел выгрузить ненужные чанки (GC/ChunkManager)
                    // и обработать сетевые пакеты/физику игроков
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}

                processBatch(world, queue, start, end, box);
            });
        });
    }

    private void processChunk(ServerWorld world, int cx, int cz, BlockPos start, BlockPos end, BlockBox structureBox, WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionY = chunk.sectionIndexToCoord(i);
            int sectionMinY = sectionY * 16;

            if (sectionMinY > end.getY() || sectionMinY + 15 < start.getY()) continue;

            try {
                writer.startWrite(cx, sectionY, cz, chunk, structureBox);
            } catch (IOException e) {
                LOGGER.error("Failed to write section at {}, {}, {}", cx, sectionY, cz, e);
            }
        }
    }

    private void finalizeSaving(ServerWorld world) {
        CompletableFuture.runAsync(() -> {
            try {
                writer.close();
                world.getServer().execute(() -> {
                    world.getPlayers().forEach(s -> s.sendMessage(Text.of("Structure saved by path: " + writer.filePath.toString())));
                });
            } catch (Exception e) {
                LOGGER.error("Error when flushing structure", e);
                world.getServer().execute(() -> {
                    world.getPlayers().forEach(s -> s.sendMessage(Text.of("Error when try save structure: " + e.getMessage())));
                });
            }
        });
    }
}
