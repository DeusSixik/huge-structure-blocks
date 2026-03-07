package com.convallyria.hugestructureblocks.neoforge;

import com.convallyria.hugestructureblocks.utils.StructureWandClient;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public class HSBNeoForgeClient {

    public static void init() {
        // Подключаемся к нативному ивенту NeoForge
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
//                if (StructureWandClient.getCachedBox() != null) {
//                    StructureWandClient.renderSelectionBox(
//                            event.getPoseStack(), // В зависимости от маппингов может называться getMatrixStack()
//                            event.getCamera()
//                    );
//                }

                StructureWandClient.renderSelectionBox(
                        event.getPoseStack(), // В зависимости от маппингов может называться getMatrixStack()
                        event.getCamera()
                );
            }
        });
    }
}
