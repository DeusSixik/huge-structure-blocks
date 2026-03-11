package com.convallyria.hugestructureblocks.utils.io;

import com.convallyria.hugestructureblocks.utils.data.BlockEntityData;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
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

    private final Map<WorldChunk, Integer> chunksToUpdateClient = new HashMap<>();
    private static final ChunkTicketType<ChunkPos> LOAD_TICKET = ChunkTicketType.create("bts_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SECTIONS_PER_TICK = 50;

    private final ServerWorld world;
    private final BigStructureReader reader;

    private final int offsetCx;
    private final int offsetCz;
    private final int offsetSy;

    private final List<BigStructureReader.SectionData> pendingSections = new ArrayList<>();
    private final List<BigStructureReader.EntityData> pendingEntities = new ArrayList<>();
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
                Object record = reader.readNextRecord();
                if (record == null) {
                    eofReached = true;
                } else if (record instanceof BigStructureReader.SectionData data) {
                    pendingSections.add(data);
                    ChunkPos targetPos = new ChunkPos(data.cx() + offsetCx, data.cz() + offsetCz);
                    world.getChunkManager().addTicket(LOAD_TICKET, targetPos, 2, targetPos);
                } else if (record instanceof BigStructureReader.EntityData ed) {
                    pendingEntities.add(ed); // Сохраняем моба до конца загрузки чанков
                }
            }

            pendingSections.removeIf(data -> {
                int targetCx = data.cx() + offsetCx;
                int targetCz = data.cz() + offsetCz;

                if (!world.isChunkLoaded(targetCx, targetCz)) return false;

                WorldChunk worldChunk = world.getChunk(targetCx, targetCz);
                if (worldChunk == null) return false;

                mergeIntoChunk(worldChunk, data.sy() + offsetSy, data);

                chunksToUpdateClient.putIfAbsent(worldChunk, 0);
                world.getChunkManager().removeTicket(LOAD_TICKET, new ChunkPos(targetCx, targetCz), 2, new ChunkPos(targetCx, targetCz));
                return true;
            });

            if (!chunksToUpdateClient.isEmpty()) {
                int viewDistance = world.getServer().getPlayerManager().getViewDistance();
                Iterator<Map.Entry<WorldChunk, Integer>> iterator = chunksToUpdateClient.entrySet().iterator();

                while (iterator.hasNext()) {
                    Map.Entry<WorldChunk, Integer> entry = iterator.next();
                    WorldChunk chunk = entry.getKey();
                    int age = entry.getValue();

                    if (age >= 5) {
                        ChunkPos pos = chunk.getPos();
                        net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket chunkPacket = new net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
                        net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket lightPacket = new net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket(pos, world.getLightingProvider(), null, null);

                        for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
                            if (player.getChunkPos().getChebyshevDistance(pos) <= viewDistance) {
                                player.networkHandler.send(chunkPacket, null);
                                player.networkHandler.send(lightPacket, null);
                            }
                        }
                        iterator.remove();
                    } else {
                        entry.setValue(age + 1);
                    }
                }
            }

            if (eofReached && pendingSections.isEmpty() && chunksToUpdateClient.isEmpty()) {
                if (!pendingEntities.isEmpty()) {
                    int spawned = 0;
                    while (!pendingEntities.isEmpty() && spawned < 50) {
                        spawnEntity(pendingEntities.remove(0));
                        spawned++;
                    }
                } else {
                    finish();
                }
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

            final boolean moonrise = Platform.isModLoaded("moonrise");

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

                                    if(!moonrise)
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

            for (BigStructureReader.BlockEntityData bed : data.blockEntities()) {
                int absY = sectionMinY + bed.ly();
                BlockPos globalPos = new BlockPos((cx << 4) + bed.lx(), absY, (cz << 4) + bed.lz());

                // Обновляем абсолютные координаты внутри NBT
                net.minecraft.nbt.NbtCompound nbt = bed.nbt().copy();
                nbt.putInt("x", globalPos.getX());
                nbt.putInt("y", globalPos.getY());
                nbt.putInt("z", globalPos.getZ());

                net.minecraft.block.entity.BlockEntity be = net.minecraft.block.entity.BlockEntity.createFromNbt(globalPos, chunk.getBlockState(globalPos), nbt, world.getRegistryManager());
                if (be != null) chunk.setBlockEntity(be);
            }

            section.calculateCounts();
            chunk.setNeedsSaving(true);
        }
    }

    private void spawnEntity(BigStructureReader.EntityData data) {
        net.minecraft.util.math.Vec3d pos = new net.minecraft.util.math.Vec3d(
                data.x() + reader.origin.getX() + (offsetCx * 16),
                data.y() + reader.origin.getY() + (offsetSy * 16),
                data.z() + reader.origin.getZ() + (offsetCz * 16)
        );

        net.minecraft.nbt.NbtCompound nbt = data.nbt().copy();
        nbt.remove("UUID"); // Удаляем старый UUID, чтобы не было дубликатов на сервере

        // Обновляем позицию в NBT (очень важно для корректной физики)
        net.minecraft.nbt.NbtList posList = new net.minecraft.nbt.NbtList();
        posList.add(net.minecraft.nbt.NbtDouble.of(pos.x));
        posList.add(net.minecraft.nbt.NbtDouble.of(pos.y));
        posList.add(net.minecraft.nbt.NbtDouble.of(pos.z));
        nbt.put("Pos", posList);

        net.minecraft.entity.Entity entity = net.minecraft.entity.EntityType.loadEntityWithPassengers(nbt, world, (e) -> {
            e.setPosition(pos.x, pos.y, pos.z);
            return e;
        });

        if (entity != null) {
            world.spawnEntityAndPassengers(entity);
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
