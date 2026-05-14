package com.convallyria.hugestructureblocks.utils.io;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class StableBlockStateCodec {

    private StableBlockStateCodec() {
    }

    static String encode(BlockState state) {
        StringBuilder builder = new StringBuilder(Registries.BLOCK.getId(state.getBlock()).toString());
        Map<Property<?>, Comparable<?>> entries = state.getEntries();
        if (!entries.isEmpty()) {
            List<Map.Entry<Property<?>, Comparable<?>>> sortedEntries = new ArrayList<>(entries.entrySet());
            sortedEntries.sort(Comparator.comparing(entry -> entry.getKey().getName()));

            builder.append('[');
            for (int i = 0; i < sortedEntries.size(); i++) {
                Map.Entry<Property<?>, Comparable<?>> entry = sortedEntries.get(i);
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(entry.getKey().getName())
                        .append('=')
                        .append(getPropertyValueName(entry.getKey(), entry.getValue()));
            }
            builder.append(']');
        }
        return builder.toString();
    }

    static BlockState decode(String encoded) {
        int propertiesStart = encoded.indexOf('[');
        String blockId = propertiesStart == -1 ? encoded : encoded.substring(0, propertiesStart);

        Identifier id;
        try {
            id = Identifier.of(blockId);
        } catch (RuntimeException ignored) {
            return Blocks.STRUCTURE_VOID.getDefaultState();
        }

        if (!Registries.BLOCK.containsId(id)) {
            return Blocks.STRUCTURE_VOID.getDefaultState();
        }

        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (propertiesStart == -1 || !encoded.endsWith("]")) {
            return state;
        }

        StateManager<Block, BlockState> stateManager = block.getStateManager();
        String properties = encoded.substring(propertiesStart + 1, encoded.length() - 1);
        if (properties.isEmpty()) {
            return state;
        }

        for (String propertyEntry : properties.split(",")) {
            int separator = propertyEntry.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            Property<?> property = stateManager.getProperty(propertyEntry.substring(0, separator));
            if (property == null) {
                continue;
            }

            state = withProperty(state, property, propertyEntry.substring(separator + 1));
        }
        return state;
    }

    private static <T extends Comparable<T>> String getPropertyValueName(Property<T> property, Comparable<?> value) {
        return property.name(property.getType().cast(value));
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.map(parsedValue -> state.with(property, parsedValue)).orElse(state);
    }
}
