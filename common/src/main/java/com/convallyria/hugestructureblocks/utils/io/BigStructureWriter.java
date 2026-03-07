package com.convallyria.hugestructureblocks.utils.io;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockBox;
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
import java.util.zip.GZIPOutputStream;

public class BigStructureWriter implements AutoCloseable {

    public final Path filePath;
    protected @Nullable DataOutputStream out;
    protected final PacketByteBuf buffer;

    protected final PalettedContainer<BlockState> sectionBuffer;

    public BigStructureWriter(Path filePath) {
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
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        if(this.out == null)
            this.out = createStream(filePath);
        else
            this.out.flush();


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

        // 2. HOT PATH: Копируем только нужные блоки во временный буфер
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState state = source.get(x, y, z);
                    // Опционально: можно отфильтровывать воздух, если не хочешь стирать блоки воздухом
                    if (!state.isAir()) {
                        sectionBuffer.set(x, y, z, state);
                        hasData = true;
                    }
                }
            }
        }

        if (!hasData) return; // Не пишем пустые куски

        // 3. Пишем заголовок секции и её локальные границы
        out.writeInt(cx);
        out.writeInt(sy);
        out.writeInt(cz);
        out.writeByte(minX); out.writeByte(minY); out.writeByte(minZ);
        out.writeByte(maxX); out.writeByte(maxY); out.writeByte(maxZ);

        // 4. Сериализуем отфильтрованный контейнер (Netty bit-packing)
        buffer.clear();
        sectionBuffer.writePacket(buffer);

        int length = buffer.readableBytes();
        out.writeInt(length);
        buffer.readBytes(out, length);

        // 5. HOT PATH CLEANUP: Сбрасываем только измененную зону обратно в STRUCTURE_VOID
        // Это позволяет переиспользовать один инстанс PalettedContainer для всего сохранения
        BlockState voidState = Blocks.STRUCTURE_VOID.getDefaultState();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    sectionBuffer.set(x, y, z, voidState);
                }
            }
        }
    }

    @Override
    public void close() throws Exception {
        out.flush();
        out.close();
        buffer.release();
    }
}
