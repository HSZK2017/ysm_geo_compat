package com.ysmef.geomodel.mixin.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.eftlm.YsmMaidMeshSupport;
import com.ysmef.geomodel.model.TlmModelLibrary;
import net.EFTLM.EF.Capability.MaidPatch;
import net.EFTLM.EF.Model.EFTLM_Meshes;
import net.EFTLM.EF.Model.Mesh.MaidMesh;
import net.EFTLM.EF.Render.PatchedLivingMaidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;

/**
 * Bytecode-level hijack of EpicFight_TouhouLittleMaid's maid mesh selection.
 *
 * Mirrors EFTLM's own selection condition:
 * <pre>
 *   Mesh = EFTLM_Meshes.getMesh(MaidPatch.getModelID());
 *   return Mesh != null ? Mesh : &lt;our converted mesh&gt;;
 * </pre>
 * EFTLM's built-in meshes are hand-tuned for the model packs it covers (its
 * WineFox family) and take precedence; the converted mesh is substituted only
 * for models EFTLM does not cover, replacing EFTLM's generic default mesh.
 *
 * Maids using a YSM model (EntityMaid#isYsmModel) are yielded to the
 * YSM_EpicFight_Compat mod: this handler returns without touching the return
 * value so YSMEF's own injector on this method (when installed) handles them.
 * This keeps the two mods' injectors mutually exclusive - each maid's mesh is
 * set by exactly one mod - avoiding double setReturnValue on the same
 * CallbackInfoReturnable.
 *
 * Lives in the optional eftlm mixin config (required:false) so the mod still
 * loads when EpicFight_TouhouLittleMaid is absent.
 */
@Mixin(value = PatchedLivingMaidRenderer.class, remap = false)
public abstract class YsmMaidRendererMixin {

    /**
     * Per-model mesh-source confirmation logs, deduped by (source, model id):
     * every maid model id that ever renders is logged once with the mesh source
     * actually used (EFTLM builtin mesh / EFTLM covered default / converted
     * TLM mesh / YSM yield / missing), independent of battle or idle mode.
     */
    private static final java.util.Set<String> LOGGED_SOURCES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "getMeshProvider(Lnet/EFTLM/EF/Capability/MaidPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmef$useTlmMesh(MaidPatch<EntityMaid> maidPatch,
                                  CallbackInfoReturnable<AssetAccessor<MaidMesh>> cir) {
        EntityMaid maid = maidPatch.getOriginal();
        if (maid == null) {
            return;
        }
        String modelId = maidPatch.getModelID();
        String displayName = maid.getName().getString();
        if (maid.isYsmModel()) {
            // YSM-model maids belong to YSM_EpicFight_Compat (when installed);
            // leave the return value untouched so its injector wins.
            String ysmId = maid.getYsmModelId();
            logMeshSourceOnce(ysmId != null && !ysmId.isEmpty() ? ysmId : modelId,
                    "YSM-yield (YSM_EpicFight_Compat)", displayName);
            return;
        }
        // EFTLM ships its own tuned mesh for this model id (keyed by the
        // namespace-stripped id, see MaidPatch#getModelID): prefer it.
        Meshes.MeshAccessor<MaidMesh> eftlmMesh = EFTLM_Meshes.getMesh(modelId);
        if (eftlmMesh != null) {
            logMeshSourceOnce(modelId, "EFTLM builtin mesh", displayName);
            cir.setReturnValue(eftlmMesh);
            return;
        }
        // Models on the EFTLM coverage list (e.g. winefox_blue) are rendered by
        // EFTLM's built-in meshes too (its getMeshProvider falls back to the
        // default WineFox mesh): do not substitute the converted mesh.
        if (TlmModelLibrary.isEftlmCovered(modelId)) {
            logMeshSourceOnce(modelId, "EFTLM covered (default mesh)", displayName);
            return;
        }
        // EFTLM has no mesh for this model: substitute the converted mesh.
        AssetAccessor<HumanoidMesh> mesh = YsmMaidMeshSupport.selectMaidMesh(maid);
        if (mesh != null) {
            logMeshSourceOnce(modelId, "converted TLM mesh", displayName);
            @SuppressWarnings("unchecked")
            AssetAccessor<MaidMesh> result = (AssetAccessor<MaidMesh>) (AssetAccessor<?>) mesh;
            cir.setReturnValue(result);
        } else {
            logMeshSourceOnce(modelId, "missing (EFTLM default mesh)", displayName);
        }
    }

    private static void logMeshSourceOnce(String modelId, String source, String displayName) {
        String id = modelId == null ? "<null>" : modelId;
        if (LOGGED_SOURCES.add(source + "|" + id)) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: maid '{}' (model '{}') mesh source: {} (applies to both battle and idle mode)",
                    displayName, id, source);
        }
    }
}
