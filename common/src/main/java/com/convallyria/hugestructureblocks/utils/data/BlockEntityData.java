package com.convallyria.hugestructureblocks.utils.data;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BlockEntityData {
    public static final PacketCodec<RegistryByteBuf, BlockEntityData> PACKET_CODEC = PacketCodec.of(BlockEntityData::write, BlockEntityData::new);
    public static final PacketCodec<RegistryByteBuf, List<BlockEntityData>> LIST_PACKET_CODEC = PACKET_CODEC.collect(PacketCodecs.toList());
    public final int localXz;
    public final int y;
    public final BlockEntityType<?> type;
    @Nullable
    public final NbtCompound nbt;

    public BlockEntityData(int localXz, int y, BlockEntityType<?> type, @Nullable NbtCompound nbt) {
        this.localXz = localXz;
        this.y = y;
        this.type = type;
        this.nbt = nbt;
    }

    public BlockEntityData(RegistryByteBuf buf) {
        this.localXz = buf.readByte();
        this.y = buf.readShort();
        this.type = PacketCodecs.registryValue(RegistryKeys.BLOCK_ENTITY_TYPE).decode(buf);
        this.nbt = buf.readNbt();
    }

    public void write(RegistryByteBuf buf) {
        buf.writeByte(this.localXz);
        buf.writeShort(this.y);
        PacketCodecs.registryValue(RegistryKeys.BLOCK_ENTITY_TYPE).encode(buf, this.type);
        buf.writeNbt(this.nbt);
    }

    public static BlockEntityData of(BlockEntity blockEntity) {
        NbtCompound nbtCompound = blockEntity.toInitialChunkDataNbt(blockEntity.getWorld().getRegistryManager());
        BlockPos blockPos = blockEntity.getPos();
        int i = ChunkSectionPos.getLocalCoord(blockPos.getX()) << 4 | ChunkSectionPos.getLocalCoord(blockPos.getZ());
        return new BlockEntityData(i, blockPos.getY(), blockEntity.getType(), nbtCompound.isEmpty() ? null : nbtCompound);
    }
}

