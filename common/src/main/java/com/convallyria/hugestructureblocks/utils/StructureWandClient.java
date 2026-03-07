package com.convallyria.hugestructureblocks.utils;

import com.convallyria.hugestructureblocks.HugeStructureBlocksMod;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public class StructureWandClient {
    private static BlockPos pos1 = null;
    private static BlockPos pos2 = null;

    public static Box cachedBox = null;
    private static Box cachedBox1 = null;
    private static Box cachedBox2 = null;

    public static Box getCachedBox() {
        return cachedBox;
    }

    public static void register() {
        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, direction) -> {
            if (player.getWorld().isClient && player.getMainHandStack().isOf(HugeStructureBlocksMod.WAND)) {
                pos1 = pos;
                recalculateBox();
            }
            return EventResult.pass();
        });

        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (player.getWorld().isClient && player.getMainHandStack().isOf(HugeStructureBlocksMod.WAND)) {
                pos2 = pos;
                recalculateBox();
            }
            return EventResult.pass();
        });

        HugeStructureBlocksMod.CLEAR_ZONE = () -> {
            pos1 = null;
            pos2 = null;
            cachedBox = null;
            cachedBox1 = null;
            cachedBox2 = null;
        };

    }

    public static void recalculateBox() {
        cachedBox1 = pos1 != null ? new Box(pos1).expand(0.005) : null;
        cachedBox2 = pos2 != null ? new Box(pos2).expand(0.005) : null;

        if (pos1 != null && pos2 != null) {
            cachedBox = new Box(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX() + 1, pos2.getY() + 1, pos2.getZ() + 1).expand(0.005);
        } else {
            cachedBox = null;
        }
    }

    public static void renderSelectionBox(MatrixStack matrices, Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        Vec3d camPos = camera.getPos();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer fillConsumer = immediate.getBuffer(RenderLayer.getDebugQuads());

        if (cachedBox != null) renderFilledBox(matrices, fillConsumer, cachedBox, 1.0F, 0.0F, 0.0F, 0.2F);
        if (cachedBox1 != null) renderFilledBox(matrices, fillConsumer, cachedBox1, 0.0F, 1.0F, 0.0F, 0.2F);
        if (cachedBox2 != null) renderFilledBox(matrices, fillConsumer, cachedBox2, 0.0F, 0.5F, 1.0F, 0.2F);

        immediate.draw();

        VertexConsumer lineConsumer = immediate.getBuffer(RenderLayer.getLines());

        if (cachedBox != null) WorldRenderer.drawBox(matrices, lineConsumer, cachedBox, 1.0F, 0.0F, 0.0F, 1.0F);
        if (cachedBox1 != null) WorldRenderer.drawBox(matrices, lineConsumer, cachedBox1, 0.0F, 1.0F, 0.0F, 1.0F);
        if (cachedBox2 != null) WorldRenderer.drawBox(matrices, lineConsumer, cachedBox2, 0.0F, 0.5F, 1.0F, 1.0F);

        immediate.draw();

        TextRenderer textRenderer = client.textRenderer;
        if (pos1 != null) renderFloatingText(matrices, camera, textRenderer, immediate, pos1, "Точка 1", 0xFF55FF55);
        if (pos2 != null) renderFloatingText(matrices, camera, textRenderer, immediate, pos2, "Точка 2", 0xFF5555FF);

        immediate.draw();
        matrices.pop();
    }

    private static void renderFilledBox(MatrixStack matrices, VertexConsumer vertexConsumer, Box box, float r, float g, float b, float a) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float minX = (float) box.minX; float minY = (float) box.minY; float minZ = (float) box.minZ;
        float maxX = (float) box.maxX; float maxY = (float) box.maxY; float maxZ = (float) box.maxZ;

        // Нижняя
        vertexConsumer.vertex(matrix, minX, minY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a);
        // Верхняя
        vertexConsumer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a);
        // Северная
        vertexConsumer.vertex(matrix, minX, minY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a);
        // Южная
        vertexConsumer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a);
        // Западная
        vertexConsumer.vertex(matrix, minX, minY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a);
        // Восточная
        vertexConsumer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a);
        vertexConsumer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a);
    }

    private static void renderFloatingText(MatrixStack matrices, Camera camera, TextRenderer textRenderer,
                                           VertexConsumerProvider.Immediate immediate, BlockPos pos, String text, int color) {
        matrices.push();

        matrices.translate(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);

        matrices.multiply(camera.getRotation());

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));

        matrices.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
        Text textObj = Text.literal(text);
        float width = (float) (-textRenderer.getWidth(textObj) / 2);

        textRenderer.draw(
                textObj,
                width,
                0f,
                color,
                true,
                positionMatrix,
                immediate,
                TextRenderer.TextLayerType.SEE_THROUGH,
                0,
                15728880
        );

        matrices.pop();
    }
}