package com.ysmef.geomodel.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.ysmef.geomodel.model.TlmServerOverlay;

import java.nio.file.Path;

/** Loaded only when TLM is installed; restores its local registry and applies session packs last. */
public final class TlmClientPackBridge {
    private TlmClientPackBridge() {}

    public static void reloadWithOverlays() {
        CustomPackLoader.reloadPacks();
        for (Path pack : TlmServerOverlay.activePacks()) {
            CustomPackLoader.readModelFromFolder(pack.toFile());
        }
        CustomPackLoader.MAID_MODELS.sortPackList();
        CustomPackLoader.CHAIR_MODELS.sortPackList();
    }
}
