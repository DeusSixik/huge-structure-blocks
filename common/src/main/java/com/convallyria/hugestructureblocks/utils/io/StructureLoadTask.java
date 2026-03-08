package com.convallyria.hugestructureblocks.utils.io;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.*;

import java.io.IOException;
import java.util.*;

public class StructureLoadTask {

    private final Set<WorldChunk> chunksToUpdateClient = new HashSet<>();
    private static final ChunkTicketType<ChunkPos> LOAD_TICKET = ChunkTicketType.create("bts_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SECTIONS_PER_TICK = 50;

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

            pendingSections.removeIf(data -> {
                int targetCx = data.cx() + offsetCx;
                int targetCz = data.cz() + offsetCz;

                if (!world.isChunkLoaded(targetCx, targetCz)) return false;

                WorldChunk worldChunk = world.getChunk(targetCx, targetCz);
                if (worldChunk == null) return false;

                mergeIntoChunk(worldChunk, data.sy() + offsetSy, data);

                chunksToUpdateClient.add(worldChunk);
                world.getChunkManager().removeTicket(LOAD_TICKET, new ChunkPos(targetCx, targetCz), 2, new ChunkPos(targetCx, targetCz));
                return true;
            });

            if (!chunksToUpdateClient.isEmpty() && !world.getLightingProvider().hasUpdates()) {
                int viewDistance = world.getServer().getPlayerManager().getViewDistance();

                for (WorldChunk chunk : chunksToUpdateClient) {
                    ChunkPos pos = chunk.getPos();

                    net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket chunkPacket =
                            new net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);

                    net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket lightPacket =
                            new net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket(pos, world.getLightingProvider(), null, null);

                    for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
                        if (player.getChunkPos().getChebyshevDistance(pos) <= viewDistance) {
                            player.networkHandler.send(chunkPacket, null);
                            player.networkHandler.send(lightPacket, null);
                        }
                    }
                }
                chunksToUpdateClient.clear();
            }

            if (eofReached && pendingSections.isEmpty() && chunksToUpdateClient.isEmpty()) {
                finish();
            }

        } catch (IOException e) {
            System.err.println("I/O Error during structure load: " + e.getMessage());
            finish();
        }
    }

    private void mergeIntoChunk(WorldChunk chunk, int targetSy, BigStructureReader.SectionData data) {
        ChunkSection[] sections = chunk.getSectionArray();
        int sectionIndex = chunk.sectionCoordToIndex(targetSy);

        if (sectionIndex >= 0 && sectionIndex < sections.length) {
            ChunkSection section = sections[sectionIndex];
            boolean wasEmpty = (section == null || section.isEmpty());

            if (section == null) {
                section = new ChunkSection(chunk.getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME));
                sections[sectionIndex] = section;
            }

            PalettedContainer<BlockState> worldContainer = section.getBlockStateContainer();
            PalettedContainer<BlockState> sourceContainer = data.container();
            BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();

            int sectionMinY = targetSy << 4;
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;

            if (wasEmpty) {
                world.getLightingProvider().setSectionStatus(ChunkSectionPos.from(cx, targetSy, cz), false);
            }

            worldContainer.lock();
            try {
                BlockPos.Mutable mutable = new BlockPos.Mutable();
                for (int y = data.minY(); y <= data.maxY(); y++) {
                    int absY = sectionMinY + y;
                    for (int z = data.minZ(); z <= data.maxZ(); z++) {
                        for (int x = data.minX(); x <= data.maxX(); x++) {
                            BlockState state = sourceContainer.get(x, y, z);
                            if (state != voidState) {
                                BlockState oldState = worldContainer.swapUnsafe(x, y, z, state);

                                if (oldState != state) {
                                    chunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING).trackUpdate(x, absY, z, state);
                                    chunk.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE).trackUpdate(x, absY, z, state);

                                    chunk.getChunkSkyLight().isSkyLightAccessible(chunk, x, absY, z);

                                    world.getLightingProvider().checkBlock(mutable.set((cx << 4) + x, absY, (cz << 4) + z));
                                }
                            }
                        }
                    }
                }
            } finally {
                worldContainer.unlock();
            }

            section.calculateCounts();
            chunk.setNeedsSaving(true);
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
