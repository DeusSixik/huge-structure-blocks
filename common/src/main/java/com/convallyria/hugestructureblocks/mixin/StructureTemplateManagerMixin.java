package com.convallyria.hugestructureblocks.mixin;

import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;

@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin {

    @Shadow
    public abstract Path getTemplatePath(Identifier id, String extension);

//    @Redirect(method = {"getTemplateOrBlank"}, at = @At(value = "NEW", target = "()Lnet/minecraft/structure/StructureTemplate;"))
//    public StructureTemplate bts$createStructureTemplate() {
//        return new BTSStructureTemplate(new BigStructureWriter(getTemplatePath(Identifier.of("sdm_test"), ".nbt")));
//    }
}
