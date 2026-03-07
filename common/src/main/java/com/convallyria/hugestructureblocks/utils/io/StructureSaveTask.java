package com.convallyria.hugestructureblocks.utils.io;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.util.*;

public class StructureSaveTask {

    // Кастомный тип тикета. Компаратор нужен для внутренней сортировки в ChunkManager.
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create("bts_save", Comparator.comparingLong(ChunkPos::toLong));
    // Жесткий лимит чанков в ОЗУ. Спасает от OutOfMemory при сохранении 16к зоны.
    private static final int MAX_CONCURRENT_CHUNKS = 15;

    private final ServerWorld world;
    private final BigStructureWriter writer;
    private final BlockPos start;
    private final BlockPos end;
    private final BlockBox box;

    private final Queue<ChunkPos> pendingChunks = new ArrayDeque<>();
    private final Set<ChunkPos> loadingChunks = new HashSet<>();
    private boolean isFinished = false;

    public StructureSaveTask(ServerWorld world, BigStructureWriter writer, BlockPos start, BlockPos end) {
        this.world = world;
        this.writer = writer;
        this.start = start;
        this.end = end;
        this.box = BlockBox.create(start, end);

        ChunkPos startChunk = new ChunkPos(start);
        ChunkPos endChunk = new ChunkPos(end);
        for (int cx = startChunk.x; cx <= endChunk.x; cx++) {
            for (int cz = startChunk.z; cz <= endChunk.z; cz++) {
                pendingChunks.add(new ChunkPos(cx, cz));
            }
        }
    }

    public void start() {
        world.getPlayers().forEach(p -> p.sendMessage(Text.literal("Сохранение начато... (" + pendingChunks.size() + " чанков)")));
        TickEvent.SERVER_POST.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (isFinished) return;

        if (pendingChunks.isEmpty() && loadingChunks.isEmpty()) {
            finish();
            return;
        }

        // 1. Подкидываем тикеты для не загруженных чанков
        while (loadingChunks.size() < MAX_CONCURRENT_CHUNKS && !pendingChunks.isEmpty()) {
            ChunkPos pos = pendingChunks.poll();
            loadingChunks.add(pos);
            // Радиус 2 гарантирует полную загрузку чанка (уровень 31)
            world.getChunkManager().addTicket(TICKET, pos, 2, pos);
        }

        // 2. HOT PATH: Проверяем готовность чанков без блокировки потока
        Iterator<ChunkPos> iterator = loadingChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos pos = iterator.next();

            // ВАЖНО: allowLoading = false. Если чанк еще не подгрузился с диска, метод вернет null и не повесит сервер.
            Chunk chunk = world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);

            if (chunk instanceof WorldChunk worldChunk) {
                processChunk(pos.x, pos.z, worldChunk);

                // Сразу убираем тикет. ChunkManager выгрузит его при следующей очистке памяти.
                world.getChunkManager().removeTicket(TICKET, pos, 2, pos);
                iterator.remove();
            }
        }
    }

    private void processChunk(int cx, int cz, WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionY = chunk.sectionIndexToCoord(i);
            int sectionMinY = sectionY * 16;
            if (sectionMinY > end.getY() || sectionMinY + 15 < start.getY()) continue;

            try {
                writer.startWrite(cx, sectionY, cz, chunk, box);
            } catch (IOException e) {
                System.err.println("Failed to write section at " + cx + "," + sectionY + "," + cz);
            }
        }
    }

    private void finish() {
        isFinished = true;
        try {
            writer.close();
            world.getPlayers().forEach(p -> p.sendMessage(Text.literal("Структура успешно сохранена: " + writer.filePath)));
        } catch (Exception e) {
            world.getPlayers().forEach(p -> p.sendMessage(Text.literal("Ошибка финализации: " + e.getMessage())));
        }
    }
}
