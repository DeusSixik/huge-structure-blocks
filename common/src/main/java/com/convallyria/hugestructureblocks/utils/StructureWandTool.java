package com.convallyria.hugestructureblocks.utils;

import com.convallyria.hugestructureblocks.HugeStructureBlocksMod;
import com.convallyria.hugestructureblocks.api.BTSStructuresAPI;
import com.convallyria.hugestructureblocks.api.exceptions.StructureGenerationException;
import com.convallyria.hugestructureblocks.config.BSConfig;
import com.convallyria.hugestructureblocks.utils.io.BigStructureReader;
import com.convallyria.hugestructureblocks.utils.io.BigStructureWriter;
import com.convallyria.hugestructureblocks.utils.io.StructureLoadTask;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class StructureWandTool {

    private static final Map<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();

    // Провайдер подсказок для автодополнения (Tab)
    private static final SuggestionProvider<ServerCommandSource> SUGGEST_STRUCTURES = (context, builder) -> {
        Path folder = BSConfig.STRUCTURES_FOLDER;
        if (!Files.exists(folder)) {
            return builder.buildFuture();
        }

        try (Stream<Path> paths = Files.walk(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".bin"))
                    .forEach(p -> {
                        String relativePath = folder.relativize(p).toString();
                        relativePath = relativePath.replace('\\', '/');
                        String suggestion = relativePath.substring(0, relativePath.length() - 4);

                        if (CommandSource.shouldSuggest(builder.getRemaining(), suggestion)) {
                            builder.suggest(suggestion);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return builder.buildFuture();
    };

    public static void register() {
        registerEvents();
        registerCommand();


    }

    private static void registerEvents() {
        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, direction) -> {
            if (!player.getWorld().isClient && player.getMainHandStack().isOf(HugeStructureBlocksMod.WAND) && player.hasPermissionLevel(2)) {
                SELECTIONS.computeIfAbsent(player.getUuid(), k -> new Selection()).pos1 = pos;
                player.sendMessage(Text.literal("Точка 1: " + pos.toShortString()), false);
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });

        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (!player.getWorld().isClient && player.getMainHandStack().isOf(HugeStructureBlocksMod.WAND) && player.hasPermissionLevel(2)) {
                SELECTIONS.computeIfAbsent(player.getUuid(), k -> new Selection()).pos2 = pos;
                player.sendMessage(Text.literal("Точка 2: " + pos.toShortString()), false);
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });

        InteractionEvent.RIGHT_CLICK_ITEM.register((player, hand) -> {
            if (player.isSneaking() && player.getStackInHand(hand).isOf(HugeStructureBlocksMod.WAND)) {
                if (!player.getWorld().isClient) {
                    SELECTIONS.remove(player.getUuid());
                    player.sendMessage(Text.literal("Выделение очищено"), true);
                } else {
                    HugeStructureBlocksMod.CLEAR_ZONE.run();
                }

                return CompoundEventResult.interruptTrue(player.getStackInHand(hand));
            }

            return CompoundEventResult.pass();
        });
    }

    private static void registerCommand() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("bts_structures")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("save")
                            .then(CommandManager.argument("name", StringArgumentType.string())
                                    .executes(StructureWandTool::executeSave)
                            )
                    )

                    .then(CommandManager.literal("tp")
                            .then(CommandManager.literal("point1")
                                    .executes(ctx -> executeTp(ctx, 1))
                            )
                            .then(CommandManager.literal("point2")
                                    .executes(ctx -> executeTp(ctx, 2))
                            )
                    )
                    .then(CommandManager.literal("wand")
                            .executes(StructureWandTool::executeGiveWand)
                    )

                    .then(CommandManager.literal("load")
                            .then(CommandManager.argument("name", StringArgumentType.string())
                                    .suggests(SUGGEST_STRUCTURES)
                                    .executes(StructureWandTool::executeLoad)
                            )
                    )
            );
        });
    }

    private static int executeTp(CommandContext<ServerCommandSource> context, int point) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) return 0;

        Selection sel = SELECTIONS.get(player.getUuid());
        if (sel == null) {
            source.sendError(Text.literal("У вас нет активного выделения."));
            return 0;
        }

        BlockPos targetPos = (point == 1) ? sel.pos1 : sel.pos2;
        if (targetPos == null) {
            source.sendError(Text.literal("Точка " + point + " не установлена."));
            return 0;
        }

        player.teleport(
                player.getServerWorld(),
                targetPos.getX() + 0.5,
                targetPos.getY() + 1.0,
                targetPos.getZ() + 0.5,
                player.getYaw(),
                player.getPitch()
        );

        source.sendFeedback(() -> Text.literal("Телепортация к точке " + point), false);
        return 1;
    }

    private static int executeSave(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        Selection sel = SELECTIONS.get(player.getUuid());
        if (sel == null || !sel.isComplete()) {
            source.sendError(Text.literal("Выделите 2 точки деревянным топором."));
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        Path filePath = BSConfig.STRUCTURES_FOLDER.resolve(name + ".bin");

        try {
            BlockBox box = BlockBox.create(sel.pos1, sel.pos2);

            BlockPos minPos = new BlockPos(box.getMinX(), box.getMinY(), box.getMinZ());

            BigStructureWriter writer = new BigStructureWriter(filePath, minPos);
            BTSStructureTemplate template = new BTSStructureTemplate(writer);

            template.saveFromWorld(player.getServerWorld(), minPos, box.getDimensions(), false, null);

            source.sendFeedback(() -> Text.literal("Сохранение структуры " + name + " запущено в фоне."), false);

        } catch (Exception e) {
            source.sendError(Text.literal("Ошибка: " + e.getMessage()));
            e.printStackTrace();
        }

        return 1;
    }

    private static int executeGiveWand(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) return 0;

        ItemStack wandStack = new ItemStack(HugeStructureBlocksMod.WAND);

        if (!player.getInventory().insertStack(wandStack)) {
            player.dropItem(wandStack, false);
        }

        source.sendFeedback(() -> Text.literal("Инструмент выделения (Wand) успешно выдан."), false);
        return 1;
    }

    private static class Selection {
        BlockPos pos1;
        BlockPos pos2;

        boolean isComplete() {
            return pos1 != null && pos2 != null;
        }
    }

    private static int executeLoad(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) return 0;

        String name = StringArgumentType.getString(context, "name");

        try {
            BTSStructuresAPI.loadStructure(player.getServerWorld(), player.getBlockPos(), name);
        }
        catch (Exception e) {
            source.sendError(Text.literal("Ошибка инициализации чтения: " + e.getMessage()));
        }

        return 1;
    }
}
