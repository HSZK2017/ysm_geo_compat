package com.ysmef.geomodel.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.TlmModelLibrary;
import com.ysmef.geomodel.renderer.YSMMeshSelector;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between Touhou Little Maid entities and the TLM mesh library.
 *
 * A maid's TLM model-pack GEO model (EntityMaid#getModelId, synced entity data)
 * is converted to an Epic Fight base mesh (see TlmModelLibrary). When such a
 * maid is rendered through Epic Fight's pipeline (EpicFight_TouhouLittleMaid's
 * patched renderer), the converted mesh for her model is substituted.
 *
 * Maids using a YSM model (TLM's YSM integration, EntityMaid#isYsmModel) are
 * NOT handled here: the caller (YsmMaidRendererMixin) yields those to
 * YSM_EpicFight_Compat, which renders them with its own converted YSM meshes.
 *
 * This class is only referenced when EpicFight_TouhouLittleMaid is installed
 * (the maid renderer mixin lives in the optional eftlm mixin config).
 */
public final class YsmMaidMeshSupport {

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private YsmMaidMeshSupport() {}

    /**
     * Select the converted base mesh for the maid's current TLM model-pack GEO
     * model, or null when her model has no converted mesh, leaving
     * EpicFight_TouhouLittleMaid's own mesh selection in place.
     */
    public static AssetAccessor<HumanoidMesh> selectMaidMesh(EntityMaid maid) {
        if (maid == null || maid.isYsmModel()) {
            return null;
        }
        String tlmModelId = maid.getModelId();
        if (tlmModelId == null || tlmModelId.isEmpty()) {
            return null;
        }
        logHookActiveOnce(tlmModelId);
        TlmModelLibrary.ensureGenerated();
        TlmModelLibrary.TlmMeshEntry entry = TlmModelLibrary.find(tlmModelId);
        if (entry == null) {
            return null;
        }
        return YSMMeshSelector.selectResolvedMesh(maid, entry.accessor(), tlmModelId,
                entry.texture(), "", maid.getName().getString());
    }

    /**
     * One-time proof that the maid renderer hook actually fires in-game (if this
     * line never appears in the log, the mixin did not apply).
     */
    private static void logHookActiveOnce(String modelId) {
        if (LOGGED.add("hook-active")) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: maid mesh hook active (first maid model='{}')", modelId);
        }
    }
}
