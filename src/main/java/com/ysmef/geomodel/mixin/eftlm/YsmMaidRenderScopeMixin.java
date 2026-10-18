package com.ysmef.geomodel.mixin.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.geomodel.renderer.layer.YsmWeaponPoseBridge;
import com.ysmef.geomodel.renderer.layer.YsmMaidItemLayers;
import net.EFTLM.EF.Capability.MaidPatch;
import net.EFTLM.EF.Render.PatchedMaidRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import com.ysmef.geomodel.renderer.layer.YsmAnchoredItemLayer;

import java.util.IdentityHashMap;
import java.util.Map;

/** Tie mesh drawing and the later held-item layer to the same maid and patch. */
@Mixin(value = PatchedMaidRenderer.class, remap = false)
public abstract class YsmMaidRenderScopeMixin {

    @Unique
    @SuppressWarnings("rawtypes")
    private final Map<PatchedLayer, YsmAnchoredItemLayer> ysmgeo$itemLayerWrappers = new IdentityHashMap<>();

    @Inject(method = "render(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Lnet/EFTLM/EF/Capability/MaidPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            at = @At("HEAD"), remap = false, require = 0)
    private void ysmgeo$beginWeaponPoseScope(EntityMaid maid, MaidPatch<?> patch,
                                              LivingEntityRenderer<?, ?> renderer, MultiBufferSource buffer,
                                              PoseStack poseStack, int packedLight, float partialTicks,
                                              CallbackInfo ci) {
        PatchedLivingEntityRendererLayersAccessor layers =
                (PatchedLivingEntityRendererLayersAccessor) (Object) this;
        YsmMaidItemLayers.wrapAll(layers.ysmgeo$getPatchedLayers(),
                layers.ysmgeo$getCustomLayers(), ysmgeo$itemLayerWrappers);
        YsmWeaponPoseBridge.begin(maid, patch);
    }

    @Inject(method = "render(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Lnet/EFTLM/EF/Capability/MaidPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            at = @At("RETURN"), remap = false, require = 0)
    private void ysmgeo$endWeaponPoseScope(EntityMaid maid, MaidPatch<?> patch,
                                            LivingEntityRenderer<?, ?> renderer, MultiBufferSource buffer,
                                            PoseStack poseStack, int packedLight, float partialTicks,
                                            CallbackInfo ci) {
        YsmWeaponPoseBridge.end();
    }
}
