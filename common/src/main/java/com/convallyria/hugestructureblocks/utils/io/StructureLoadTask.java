package com.convallyria.hugestructureblocks.utils.io;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StructureLoadTask {

    private static final ChunkTicketType<ChunkPos> LOAD_TICKET = ChunkTicketType.create("bts_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SECTIONS_PER_TICK = 50; // Батчинг чтения

    private final ServerWorld world;
    private final BigStructureReader reader;

    private final int offsetCx;
    private final int offsetCz;
    private final int offsetSy;

    private final List<BigStructureReader.SectionData> pendingSections = new ArrayList<>();
    private boolean eofReached = false;
    private boolean isFinished = false;

    public StructureLoadTask(ServerWorld world, BigStructureReader reader, int offsetCx, int offsetSy, int offsetCz) {
        this.world = world;
        this.reader = reader;
        this.offsetCx = offsetCx;
        this.offsetSy = offsetSy;
        this.offsetCz = offsetCz;
    }

    public void start() {
        world.getPlayers().forEach(p -> p.sendMessage(Text.literal("Начата загрузка структуры...")));
        TickEvent.SERVER_POST.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (isFinished) return;

        try {
            while (!eofReached && pendingSections.size() < SECTIONS_PER_TICK) {
                BigStructureReader.SectionData data = reader.readNextSection();
                if (data == null) {
                    eofReached = true;
                } else {
                    pendingSections.add(data);
                    ChunkPos targetPos = new ChunkPos(data.cx() + offsetCx, data.cz() + offsetCz);
                    world.getChunkManager().addTicket(LOAD_TICKET, targetPos, 2, targetPos);
                }
            }

            pendingSections.removeIf(this::tryApplySection);

            if (eofReached && pendingSections.isEmpty()) {
                finish();
            }

        } catch (IOException e) {
            System.err.println("I/O Error during structure load: " + e.getMessage());
            finish();
        }
    }

    private boolean tryApplySection(BigStructureReader.SectionData data) {
        int targetCx = data.cx() + offsetCx;
        int targetCz = data.cz() + offsetCz;
        int targetSy = data.sy() + offsetSy;

        ChunkPos targetPos = new ChunkPos(targetCx, targetCz);

        if (!world.isChunkLoaded(targetCx, targetCz)) {
            return false;
        }

        WorldChunk worldChunk = world.getChunk(targetCx, targetCz);
        if (worldChunk == null) return false;

        mergeIntoChunk(worldChunk, targetSy, data);

        world.getChunkManager().removeTicket(LOAD_TICKET, targetPos, 2, targetPos);
        return true;
    }

    private void mergeIntoChunk(WorldChunk chunk, int targetSy, BigStructureReader.SectionData data) {
        ChunkSection[] sections = chunk.getSectionArray();
        int sectionIndex = chunk.sectionCoordToIndex(targetSy);

        if (sectionIndex >= 0 && sectionIndex < sections.length) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                section = new ChunkSection(chunk.getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME));
                sections[sectionIndex] = section;
            }

            PalettedContainer<BlockState> worldContainer = section.getBlockStateContainer();
            PalettedContainer<BlockState> sourceContainer = data.container();
            BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();

            worldContainer.lock();
            try {
                for (int y = data.minY(); y <= data.maxY(); y++) {
                    for (int z = data.minZ(); z <= data.maxZ(); z++) {
                        for (int x = data.minX(); x <= data.maxX(); x++) {
                            BlockState state = sourceContainer.get(x, y, z);
                            if (state != voidState) {
                                worldContainer.swapUnsafe(x, y, z, state);
                            }
                        }
                    }
                }
            } finally {
                worldContainer.unlock();
            }

            chunk.setNeedsSaving(true);

            ChunkPos pos = chunk.getPos();
            net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket packet =
                    new net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket(
                            chunk,
                            world.getLightingProvider(),
                            new java.util.BitSet(),
                            new java.util.BitSet()
                    );

            int viewDistance = world.getServer().getPlayerManager().getViewDistance();
            for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
                if (player.getChunkPos().getChebyshevDistance(pos) <= viewDistance) {
                    player.networkHandler.send(packet, null);
                }
            }
        }
    }

    private void finish() {
        isFinished = true;
        try {
            reader.close();
            world.getPlayers().forEach(p -> p.sendMessage(Text.literal("Структура загружена!")));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
