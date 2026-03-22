package com.convallyria.hugestructureblocks.api.exceptions;

public class StructureNotFoundException extends StructureGenerationException {
    public StructureNotFoundException(String structureName) {
        super("Structure '" + structureName + "' not found!");
    }
}
