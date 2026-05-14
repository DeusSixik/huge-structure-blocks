package com.convallyria.hugestructureblocks.utils.io;

import dev.architectury.event.events.common.TickEvent;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightingProvider;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class StructureLoadTask {

    private final Reference2IntOpenHashMap<WorldChunk> chunksToUpdateClient = new Reference2IntOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<WorldChunk, CompletableFuture<?>> chunkLightBarriers = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<WorldChunk, LongSet> chunkLightTickets = new Reference2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap loadTicketRefs = new Long2IntOpenHashMap();
    private final ReferenceOpenHashSet<WorldChunk> changedChunksBuffer = new ReferenceOpenHashSet<>();
    private static final ChunkTicketType<ChunkPos> LOAD_TICKET = ChunkTicketType.create("bts_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SECTIONS_PER_TICK = 50;
    private static final int CLIENT_UPDATE_DELAY_TICKS = 5;

    private final ServerWorld world;
    private final BigStructureReader reader;

    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;

    private final List<BigStructureReader.SectionData> pendingSections = new ArrayList<>();
    private final Deque<BigStructureReader.EntityData> pendingEntities = new ArrayDeque<>();
    private boolean eofReached = false;
    private boolean isFinished = false;

    private final CompletableFuture<Void> endFuture;

    public StructureLoadTask(ServerWorld world, BigStructureReader reader, int offsetX, int offsetY, int offsetZ) {
        this.world = world;
        this.reader = reader;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.endFuture = new CompletableFuture<>();
        this.loadTicketRefs.defaultReturnValue(0);
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
                            retainLoadTicket(ChunkPos.toLong(cx, cz));
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
                        releaseLoadTicket(ChunkPos.toLong(cx, cz));
                    }
                }
                return true;
            });

            if (!chunksToUpdateClient.isEmpty()) {
                int viewDistance = world.getServer().getPlayerManager().getViewDistance();
                ObjectIterator<Reference2IntMap.Entry<WorldChunk>> iterator = Reference2IntMaps.fastIterator(chunksToUpdateClient);

                while (iterator.hasNext()) {
                    Reference2IntMap.Entry<WorldChunk> entry = iterator.next();
                    WorldChunk chunk = entry.getKey();
                    int age = entry.getIntValue();
                    ChunkPos pos = chunk.getPos();

                    if (age >= CLIENT_UPDATE_DELAY_TICKS && isChunkLightingReady(chunk, pos)) {
                        net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket chunkPacket = new net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
                        net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket lightPacket = new net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket(pos, world.getLightingProvider(), null, null);

                        for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
                            if (player.getChunkPos().getChebyshevDistance(pos) <= viewDistance) {
                                player.networkHandler.send(chunkPacket, null);
                                player.networkHandler.send(lightPacket, null);
                            }
                        }
                        chunkLightBarriers.remove(chunk);
                        releaseChunkLightTickets(chunk);
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
                        spawnEntity(pendingEntities.removeFirst());
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

    private boolean isChunkLightingReady(WorldChunk chunk, ChunkPos pos) {
        CompletableFuture<?> barrier = chunkLightBarriers.computeIfAbsent(chunk, ignored -> ((ServerLightingProvider) world.getLightingProvider()).light(chunk, false));
        return barrier.isDone();
    }

    private void mergeSectionIntoWorld(BigStructureReader.SectionData data) {
        BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();

        int minGx = (data.cx() << 4) + data.minX() + offsetX;
        int maxGx = (data.cx() << 4) + data.maxX() + offsetX;
        int minGy = (data.sy() << 4) + data.minY() + offsetY;
        int maxGy = (data.sy() << 4) + data.maxY() + offsetY;
        int minGz = (data.cz() << 4) + data.minZ() + offsetZ;
        int maxGz = (data.cz() << 4) + data.maxZ() + offsetZ;

        int minCx = minGx >> 4; int maxCx = maxGx >> 4;
        int minSy = minGy >> 4; int maxSy = maxGy >> 4;
        int minCz = minGz >> 4; int maxCz = maxGz >> 4;

        LightingProvider lightingProvider = world.getLightingProvider();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        ReferenceOpenHashSet<WorldChunk> changedChunks = changedChunksBuffer;
        changedChunks.clear();
        BlockState[] sourcePalette = data.palette();
        char[] sourceIds = data.blockStateIds();
        PalettedContainer<BlockState> sourceContainer = data.container();
        boolean stableSource = sourcePalette != null && sourceIds != null;

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

                    PalettedContainer<BlockState> targetContainer = targetSection.getBlockStateContainer();
                    net.minecraft.world.Heightmap motionBlocking = targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING);
                    net.minecraft.world.Heightmap motionBlockingNoLeaves = targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);
                    net.minecraft.world.Heightmap oceanFloor = targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR);
                    net.minecraft.world.Heightmap worldSurface = targetChunk.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE);

                    int startGx = Math.max(tCx << 4, minGx);
                    int endGx = Math.min((tCx << 4) + 15, maxGx);
                    int startGy = Math.max(tSy << 4, minGy);
                    int endGy = Math.min((tSy << 4) + 15, maxGy);
                    int startGz = Math.max(tCz << 4, minGz);
                    int endGz = Math.min((tCz << 4) + 15, maxGz);

                    boolean sectionChanged = false;
                    targetContainer.lock();
                    try {
                        for (int gy = startGy; gy <= endGy; gy++) {
                            int y = gy - offsetY - (data.sy() << 4);
                            int sourceYIndex = y << 8;
                            int ly = gy & 15;

                            for (int gz = startGz; gz <= endGz; gz++) {
                                int z = gz - offsetZ - (data.cz() << 4);
                                int sourceZIndex = z << 4;
                                int lz = gz & 15;

                                for (int gx = startGx; gx <= endGx; gx++) {
                                    int x = gx - offsetX - (data.cx() << 4);
                                    int lx = gx & 15;

                                    BlockState state = stableSource
                                            ? sourcePalette[sourceIds[sourceYIndex | sourceZIndex | x]]
                                            : sourceContainer.get(x, y, z);
                                    if (state != voidState) {
                                        BlockState oldState = targetContainer.swapUnsafe(lx, ly, lz, state);

                                        if (oldState != state) {
                                            motionBlocking.trackUpdate(lx, gy, lz, state);
                                            motionBlockingNoLeaves.trackUpdate(lx, gy, lz, state);
                                            oceanFloor.trackUpdate(lx, gy, lz, state);
                                            worldSurface.trackUpdate(lx, gy, lz, state);

                                            mutable.set(gx, gy, gz);
                                            if (ChunkLightProvider.needsLightUpdate(targetChunk, mutable, oldState, state)) {
                                                lightingProvider.checkBlock(mutable);
                                            }

                                            targetChunk.setNeedsSaving(true);
                                            changedChunks.add(targetChunk);
                                            sectionChanged = true;
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        targetContainer.unlock();
                    }

                    if (sectionChanged) {
                        targetSection.calculateCounts();

                        boolean isEmpty = targetSection.isEmpty();
                        if (wasEmpty != isEmpty) {
                            lightingProvider.setSectionStatus(net.minecraft.util.math.ChunkSectionPos.from(tCx, tSy, tCz), isEmpty);
                        }
                    }
                }
            }
        }

        for (BigStructureReader.BlockEntityData bed : data.blockEntities()) {
            if (data.getState(bed.lx(), bed.ly(), bed.lz()) == voidState) {
                continue;
            }

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
                    changedChunks.add(chunk);
                }
            }
        }

        for (WorldChunk changedChunk : changedChunks) {
            changedChunk.getChunkSkyLight().refreshSurfaceY(changedChunk);
            markChunkChanged(changedChunk);
        }
    }

    private void markChunkChanged(WorldChunk chunk) {
        chunksToUpdateClient.put(chunk, 0);
        chunkLightBarriers.remove(chunk);
        chunkLightTickets.computeIfAbsent(chunk, this::retainChunkAndLightingNeighbors);
    }

    private LongSet retainChunkAndLightingNeighbors(WorldChunk chunk) {
        LongSet retained = new LongOpenHashSet(9);
        ChunkPos center = chunk.getPos();
        for (int cx = center.x - 1; cx <= center.x + 1; cx++) {
            for (int cz = center.z - 1; cz <= center.z + 1; cz++) {
                long pos = ChunkPos.toLong(cx, cz);
                retained.add(pos);
                retainLoadTicket(pos);
            }
        }
        return retained;
    }

    private void releaseChunkLightTickets(WorldChunk chunk) {
        LongSet retained = chunkLightTickets.remove(chunk);
        if (retained == null) {
            return;
        }
        LongIterator iterator = retained.iterator();
        while (iterator.hasNext()) {
            releaseLoadTicket(iterator.nextLong());
        }
    }

    private void retainLoadTicket(long packedPos) {
        int refs = loadTicketRefs.get(packedPos);
        if (refs == 0) {
            ChunkPos pos = new ChunkPos(packedPos);
            world.getChunkManager().addTicket(LOAD_TICKET, pos, 2, pos);
            loadTicketRefs.put(packedPos, 1);
        } else {
            loadTicketRefs.put(packedPos, refs + 1);
        }
    }

    private void releaseLoadTicket(long packedPos) {
        int refs = loadTicketRefs.get(packedPos);
        if (refs <= 0) {
            return;
        }
        if (refs == 1) {
            loadTicketRefs.remove(packedPos);
            ChunkPos pos = new ChunkPos(packedPos);
            world.getChunkManager().removeTicket(LOAD_TICKET, pos, 2, pos);
        } else {
            loadTicketRefs.put(packedPos, refs - 1);
        }
    }

    private void releaseAllLoadTickets() {
        LongIterator iterator = loadTicketRefs.keySet().iterator();
        while (iterator.hasNext()) {
            ChunkPos pos = new ChunkPos(iterator.nextLong());
            world.getChunkManager().removeTicket(LOAD_TICKET, pos, 2, pos);
        }
        loadTicketRefs.clear();
        chunkLightTickets.clear();
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
            releaseAllLoadTickets();
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
