package com.convallyria.hugestructureblocks.utils;

import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.chunk.PalettedContainer;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class BinaryStructureWriter implements IChunkStorageWriter {
    private final DataOutputStream out;
    // Используем Netty-буфер для zero-allocation сериализации
    private final PacketByteBuf buffer;

    public BinaryStructureWriter(Path filePath) throws IOException {
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        // Обязательно оборачиваем в BufferedOutputStream с большим буфером (64KB)
        // GZIPOutputStream сожмет потоковые данные "на лету"
        this.out = new DataOutputStream(
                new BufferedOutputStream(
                        new GZIPOutputStream(Files.newOutputStream(filePath), 65536)
                )
        );
        this.buffer = new PacketByteBuf(Unpooled.buffer());
    }

    @Override
    public void writeSection(int cx, int sy, int cz, PalettedContainer<BlockState> container) {
        try {
            // Пишем координаты секции
            out.writeInt(cx);
            out.writeInt(sy);
            out.writeInt(cz);

            // HOT PATH: Оптимизация памяти и CPU
            buffer.clear(); // Переиспользуем один инстанс буфера

            // Используем ванильную сетевую сериализацию: она уже реализует
            // сжатие палитры (Palette reduction) и упаковку в bit-array (long[])
//            container.write(buffer, Block.STATE_IDS, container.getPaletteProvider());

            container.writePacket(buffer);

            int length = buffer.readableBytes();
            out.writeInt(length); // Пишем размер данных секции
            buffer.readBytes(out, length); // Прямой перенос байт из Off-heap памяти Netty в Stream

        } catch (IOException e) {
            throw new RuntimeException("Failed to write chunk section", e);
        }
    }

    @Override
    public void writeBlockEntities(List<NbtCompound> blockEntities) {
        try {
            out.writeInt(Integer.MAX_VALUE); // Маркер начала тайлов (вместо координаты X)
            out.writeInt(blockEntities.size());
            for (NbtCompound nbt : blockEntities) {
                NbtIo.write(nbt, out); // Синхронно пишем NBT
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write block entities", e);
        }
    }

    @Override
    public void flushAndClose() throws Exception {
        out.flush();
        out.close();
        buffer.release(); // Обязательное освобождение Off-heap памяти, иначе Memory Leak
    }

    @Override
    public void close() throws Exception {
        flushAndClose();
    }
}
