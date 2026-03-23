package com.convallyria.hugestructureblocks.utils.io;

import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class StructureLoadTask {

    private final Map<WorldChunk, Integer> chunksToUpdateClient = new HashMap<>();
    private static final ChunkTicketType<ChunkPos> LOAD_TICKET = ChunkTicketType.create("bts_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SECTIONS_PER_TICK = 50;

    private final ServerWorld world;
    private final BigStructureReader reader;

    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final boolean moonriseLoaded;

    private final List<BigStructureReader.SectionData> pendingSections = new ArrayList<>();
    private final List<BigStructureReader.EntityData> pendingEntities = new ArrayList<>();
    private boolean eofReached = false;
    private boolean isFinished = false;

    private final CompletableFuture<Void> endFuture;

    public StructureLoadTask(ServerWorld world, BigStructureReader reader, int offsetX, int offsetY, int offsetZ) {
        this.world = world;
        this.reader = reader;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.moonriseLoaded = Platform.isModLoaded("moonrise");
        this.endFuture = new CompletableFuture<>();
    }

    public void start() {
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

                    int minGx = (data.cx() << 4) + data.minX() + offsetX;
                    int maxGx = (data.cx() << 4) + data.maxX() + offsetX;
                    int minGz = (data.cz() << 4) + data.minZ() + offsetZ;
                    int maxGz = (data.cz() << 4) + data.maxZ() + offsetZ;

                    int minCx = minGx >> 4; int maxCx = maxGx >> 4;
                    int minCz = minGz >> 4; int maxCz = maxGz >> 4;

                    for (int cx = minCx; cx <= maxCx; cx++) {
                        for (int cz = minCz; cz <= maxCz; cz++) {
                            ChunkPos targetPos = new ChunkPos(cx, cz);
                            world.getChunkManager().addTicket(LOAD_TICKET, targetPos, 2, targetPos);
                        }
                    }
                } else if (record instanceof BigStructureReader.EntityData ed) {
                    pendingEntities.add(ed);
                }
            }

            pendingSections.removeIf(data -> {
                int minGx = (data.cx() << 4) + data.minX() + offsetX;
                int maxGx = (data.cx() << 4) + data.maxX() + offsetX;
                int minGz = (data.cz() << 4) + data.minZ() + offsetZ;
                int maxGz = (data.cz() << 4) + data.maxZ() + offsetZ;

                int minCx = minGx >> 4; int maxCx = maxGx >> 4;
                int minCz = minGz >> 4; int maxCz = maxGz >> 4;

                for (int cx = minCx; cx <= maxCx; cx++) {
                    for (int cz = minCz; cz <= maxCz; cz++) {
                        if (!world.isChunkLoaded(cx, cz)) return false;
                        if (world.getChunk(cx, cz) == null) return false;
                    }
                }

                mergeSectionIntoWorld(data);

                for (int cx = minCx; cx <= maxCx; cx++) {
                    for (int cz = minCz; cz <= maxCz; cz++) {
                        ChunkPos targetPos = new ChunkPos(cx, cz);
                        world.getChunkManager().removeTicket(LOAD_TICKET, targetPos, 2, targetPos);
                    }
                }
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
            endFuture.completeExceptionally(e);
            finish();
        }
    }

    private void mergeSectionIntoWorld(BigStructureReader.SectionData data) {
        BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();
        PalettedContainer<BlockState> sourceContainer = data.container();

        int minGx = (data.cx() << 4) + data.minX() + offsetX;
        int maxGx = (data.cx() << 4) + data.maxX() + offsetX;
        int minGy = (data.sy() << 4) + data.minY() + offsetY;
        int maxGy = (data.sy() << 4) + data.maxY() + offsetY;
        int minGz = (data.cz() << 4) + data.minZ() + offsetZ;
        int maxGz = (data.cz() << 4) + data.maxZ() + offsetZ;

        int minCx = minGx >> 4; int maxCx = maxGx >> 4;
        int minSy = minGy >> 4; int maxSy = maxGy >> 4;
        int minCz = minGz >> 4; int maxCz = maxGz >> 4;

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int tCx = minCx; tCx <= maxCx; tCx++) {
            for (int tCz = minCz; tCz <= maxCz; tCz++) {
                WorldChunk targetChunk = world.getChunk(tCx, tCz);
                if (targetChunk == null) continue;

                for (int tSy = minSy; tSy <= maxSy; tSy++) {
                    int sectionIndex = targetChunk.sectionCoordToIndex(tSy);
                    if (sectionIndex < 0 || sectionIndex >= targetChunk.getSectionArray().length) continue;

                    ChunkSection targetSection = targetChunk.getSectionArray()[sectionIndex];
                    boolean wasEmpty = (targetSection == null || targetSection.isEmpty());

                    if (targetSection == null) {
                        targetSection = new ChunkSection(world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME));
                        targetChunk.getSectionArray()[sectionIndex] = targetSection;
                    }

                    if (wasEmpty) {
                        world.getLightingProvider().setSectionStatus(net.minecraft.util.math.ChunkSectionPos.from(tCx, tSy, tCz), false);
                    }

                    PalettedContainer<BlockState> targetContainer = targetSection.getBlockStateContainer();

                    int startGx = Math.max(tCx << 4, minGx);
                    int endGx = Math.min((tCx << 4) + 15, maxGx);
                    int startGy = Math.max(tSy << 4, minGy);
                    int endGy = Math.min((tSy << 4) + 15, maxGy);
                    int startGz = Math.max(tCz << 4, minGz);
                    int endGz = Math.min((tCz << 4) + 15, maxGz);

                    targetContainer.lock();
                    try {
                        for (int gy = startGy; gy <= endGy; gy++) {
                            int y = gy - offsetY - (data.sy() << 4);
                            int ly = gy & 15;

                            for (int gz = startGz; gz <= endGz; gz++) {
                                int z = gz - offsetZ - (data.cz() << 4);
                                int lz = gz & 15;

                                for (int gx = startGx; gx <= endGx; gx++) {
                                    int x = gx - offsetX - (data.cx() << 4);
                                    int lx = gx & 15;

                                    BlockState state = sourceContainer.get(x, y, z);
                                    if (state != voidState) {
                                        BlockState oldState = targetContainer.swapUnsafe(lx, ly, lz, state);

                                        if (oldState != state) {
                                            targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING).trackUpdate(lx, gy, lz, state);
                                            targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE).trackUpdate(lx, gy, lz, state);

                                            if (!moonriseLoaded) {
                                                targetChunk.getChunkSkyLight().isSkyLightAccessible(targetChunk, lx, gy, lz);
                                            }

                                            world.getLightingProvider().checkBlock(mutable.set(gx, gy, gz));

                                            targetChunk.setNeedsSaving(true);
                                            chunksToUpdateClient.putIfAbsent(targetChunk, 0);
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        targetContainer.unlock();
                    }
                    targetSection.calculateCounts();
                }
            }
        }

        for (BigStructureReader.BlockEntityData bed : data.blockEntities()) {
            int gx = (data.cx() << 4) + bed.lx() + offsetX;
            int gy = (data.sy() << 4) + bed.ly() + offsetY;
            int gz = (data.cz() << 4) + bed.lz() + offsetZ;

            BlockPos globalPos = new BlockPos(gx, gy, gz);
            WorldChunk chunk = world.getChunk(gx >> 4, gz >> 4);

            if (chunk != null) {
                net.minecraft.nbt.NbtCompound nbt = bed.nbt().copy();
                nbt.putInt("x", globalPos.getX());
                nbt.putInt("y", globalPos.getY());
                nbt.putInt("z", globalPos.getZ());

                net.minecraft.block.entity.BlockEntity be = net.minecraft.block.entity.BlockEntity.createFromNbt(globalPos, chunk.getBlockState(globalPos), nbt, world.getRegistryManager());
                if (be != null) {
                    chunk.setBlockEntity(be);
                    chunksToUpdateClient.putIfAbsent(chunk, 0);
                }
            }
        }
    }

    private void spawnEntity(BigStructureReader.EntityData data) {
        net.minecraft.util.math.Vec3d pos = new net.minecraft.util.math.Vec3d(
                data.x() + reader.origin.getX() + offsetX,
                data.y() + reader.origin.getY() + offsetY,
                data.z() + reader.origin.getZ() + offsetZ
        );

        net.minecraft.nbt.NbtCompound nbt = data.nbt().copy();
        nbt.remove("UUID");

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
        if (isFinished) return;
        isFinished = true;

        try {
            reader.close();
            if (!endFuture.isDone()) {
                endFuture.complete(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!endFuture.isDone()) {
                endFuture.completeExceptionally(e);
            }
        }
    }

    public CompletableFuture<Void> getFuture() {
        return endFuture;
    }
}
