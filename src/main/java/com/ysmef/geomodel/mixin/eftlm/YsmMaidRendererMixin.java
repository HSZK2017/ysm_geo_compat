package com.ysmef.geomodel.mixin.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * Lives in the optional eftlm mixin config (required:false) so the mod still
 * loads when EpicFight_TouhouLittleMaid is absent.
 */
@Mixin(value = PatchedLivingMaidRenderer.class, remap = false)
public abstract class YsmMaidRendererMixin {

    @Inject(method = "getMeshProvider(Lnet/EFTLM/EF/Capability/MaidPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmef$useYsmMesh(MaidPatch<EntityMaid> maidPatch,
                                  CallbackInfoReturnable<AssetAccessor<MaidMesh>> cir) {
        EntityMaid maid = maidPatch.getOriginal();
        if (maid == null) {
            return;
        }
        String modelId = maidPatch.getModelID();
        // EFTLM ships its own tuned mesh for this model id (keyed by the
        // namespace-stripped id, see MaidPatch#getModelID): prefer it.
        Meshes.MeshAccessor<MaidMesh> eftlmMesh = EFTLM_Meshes.getMesh(modelId);
        if (eftlmMesh != null) {
            cir.setReturnValue(eftlmMesh);
            return;
        }
        // Models on the EFTLM coverage list (e.g. winefox_blue) are rendered by
        // EFTLM's built-in meshes too (its getMeshProvider falls back to the
        // default WineFox mesh): do not substitute the converted mesh.
        if (TlmModelLibrary.isEftlmCovered(modelId)) {
            return;
        }
        // EFTLM has no mesh for this model: substitute the converted mesh.
        AssetAccessor<HumanoidMesh> mesh = YsmMaidMeshSupport.selectMaidMesh(maid);
        if (mesh != null) {
            @SuppressWarnings("unchecked")
            AssetAccessor<MaidMesh> result = (AssetAccessor<MaidMesh>) (AssetAccessor<?>) mesh;
            cir.setReturnValue(result);
        }
    }
}
