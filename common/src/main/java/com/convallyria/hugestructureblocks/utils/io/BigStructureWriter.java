package com.convallyria.hugestructureblocks.utils.io;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class BigStructureWriter implements AutoCloseable {

    public final BlockPos origin;
    public final Path filePath;
    protected @Nullable DataOutputStream out;
    protected final PacketByteBuf buffer;

    protected final PalettedContainer<BlockState> sectionBuffer;

    public BigStructureWriter(Path filePath, BlockPos origin) {
        this.origin = origin;
        this.filePath = filePath;
        this.buffer = new PacketByteBuf(Unpooled.buffer());

        this.sectionBuffer = new PalettedContainer<>(
                Block.STATE_IDS,
                Blocks.STRUCTURE_VOID.getDefaultState(),
                PalettedContainer.PaletteProvider.BLOCK_STATE
        );
    }

    protected DataOutputStream createStream(Path filePath) throws IOException {
        return new DataOutputStream(
                new BufferedOutputStream(
                        new GZIPOutputStream(Files.newOutputStream(filePath), 65536)
                )
        );
    }

    public void startWrite(int cx, int sy, int cz, WorldChunk chunk, BlockBox structureBox) throws IOException {
        if (this.out == null) {
            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            this.out = createStream(filePath);

            // ВАЖНО: Сохраняем точку отсчета в заголовок файла
            this.out.writeInt(origin.getX());
            this.out.writeInt(origin.getY());
            this.out.writeInt(origin.getZ());
        }
        writeSection(cx, sy, cz, chunk, structureBox);
    }

    protected void writeSection(int cx, int sy, int cz, WorldChunk chunk, BlockBox structureBox) throws IOException {
        ChunkSection[] sections = chunk.getSectionArray();
        int sectionIndex = chunk.sectionCoordToIndex(sy);

        if (sectionIndex < 0 || sectionIndex >= sections.length) return;
        ChunkSection section = sections[sectionIndex];
        if (section == null || section.isEmpty()) return;

        // 1. Вычисляем локальные границы пересечения секции и BoundingBox структуры (от 0 до 15)
        int minX = Math.max(0, structureBox.getMinX() - (cx << 4));
        int maxX = Math.min(15, structureBox.getMaxX() - (cx << 4));
        int minY = Math.max(0, structureBox.getMinY() - (sy << 4));
        int maxY = Math.min(15, structureBox.getMaxY() - (sy << 4));
        int minZ = Math.max(0, structureBox.getMinZ() - (cz << 4));
        int maxZ = Math.min(15, structureBox.getMaxZ() - (cz << 4));

        // Если секция вообще не попадает в границы структуры — пропускаем
        if (minX > maxX || minY > maxY || minZ > maxZ) return;

        PalettedContainer<BlockState> source = section.getBlockStateContainer();
        boolean hasData = false;

        List<BEData> blockEntities = new ArrayList<>();

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState state = source.get(x, y, z);
                    if (!state.isAir()) {
                        sectionBuffer.set(x, y, z, state);
                        hasData = true;

                        // Сбор BlockEntity
                        if (state.hasBlockEntity()) {
                            BlockPos globalPos = new BlockPos((cx << 4) + x, (sy << 4) + y, (cz << 4) + z);
                            net.minecraft.block.entity.BlockEntity be = chunk.getBlockEntity(globalPos, WorldChunk.CreationType.IMMEDIATE);
                            if (be != null) {
                                net.minecraft.nbt.NbtCompound nbt = be.createNbtWithId(chunk.getWorld().getRegistryManager());
                                if (nbt != null)
                                    blockEntities.add(new BEData(x, y, z, nbt));
                            }
                        }
                    }
                }
            }
        }

        if (!hasData) return; // Не пишем пустые куски

        // 3. Пишем заголовок секции и её локальные границы
        out.writeByte(1);

        out.writeInt(cx); out.writeInt(sy); out.writeInt(cz);
        out.writeByte(minX); out.writeByte(minY); out.writeByte(minZ);
        out.writeByte(maxX); out.writeByte(maxY); out.writeByte(maxZ);

        buffer.clear();
        sectionBuffer.writePacket(buffer);
        int length = buffer.readableBytes();
        out.writeInt(length);
        buffer.readBytes(out, length);

        // Записываем NBT BlockEntity в конец секции
        out.writeInt(blockEntities.size());
        for (BEData be : blockEntities) {
            out.writeByte(be.lx);
            out.writeByte(be.ly);
            out.writeByte(be.lz);
            net.minecraft.nbt.NbtIo.write(be.nbt, (java.io.DataOutput) out);
        }

        BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    sectionBuffer.set(x, y, z, voidState);
                }
            }
        }
    }

    public void writeEntity(net.minecraft.entity.Entity entity) throws IOException {
        if (this.out == null) return;
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        // saveNbt вернет false для пассажиров (чтобы не дублировать) или игроков
        if (entity.saveNbt(nbt)) {
            out.writeByte(2); // ВАЖНО: Маркер 2 - Entity
            net.minecraft.util.math.Vec3d vec = entity.getPos().subtract(origin.getX(), origin.getY(), origin.getZ());
            out.writeDouble(vec.x);
            out.writeDouble(vec.y);
            out.writeDouble(vec.z);
            net.minecraft.nbt.NbtIo.write(nbt, (java.io.DataOutput) out);
        }
    }

    @Override
    public void close() throws Exception {
        if (out != null) {
            out.flush();
            out.close();
        }
        buffer.release();
    }

    private record BEData(int lx, int ly, int lz, NbtCompound nbt) {}
}
