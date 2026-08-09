package com.ysmef.geomodel.mixin.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.geomodel.eftlm.YsmMaidMeshSupport;
import net.EFTLM.EF.Capability.MaidPatch;
import net.EFTLM.EF.Model.EFTLM_Meshes;
import net.EFTLM.EF.Model.Mesh.MaidMesh;
import net.EFTLM.EF.Render.PatchedLivingMaidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

/**
 * Bytecode-level hijack of EpicFight_TouhouLittleMaid's maid mesh selection.
 *
 * PatchedLivingMaidRenderer#getMeshProvider normally picks one of EFTLM's own
 * meshes (defaulting to its WineFox mesh) regardless of the maid's model. When
 * EFTLM ships a mesh for the maid's model (MaidMeshes keyed by the stripped
 * model id), that mesh is preferred - EFTLM's meshes are hand-tuned for those
 * model packs - and the converted mesh is substituted only for models EFTLM
 * does not cover.
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
        // EFTLM has its own mesh for this model id (e.g. its WineFox family):
        // let EFTLM's getMeshProvider body run unchanged.
        if (EFTLM_Meshes.getMesh(maidPatch.getModelID()) != null) {
            return;
        }
        AssetAccessor<HumanoidMesh> mesh = YsmMaidMeshSupport.selectMaidMesh(maid);
        if (mesh != null) {
            @SuppressWarnings("unchecked")
            AssetAccessor<MaidMesh> result = (AssetAccessor<MaidMesh>) (AssetAccessor<?>) mesh;
            cir.setReturnValue(result);
        }
    }
}
