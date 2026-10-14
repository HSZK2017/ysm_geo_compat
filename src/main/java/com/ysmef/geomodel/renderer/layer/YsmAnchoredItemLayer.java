package com.ysmef.geomodel.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Pass model-sized palm poses to EF's existing held-item rendering path. */
public final class YsmAnchoredItemLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends EntityModel<E>>
        extends PatchedItemInHandLayer<E, T, M> {

    @Override
    protected void renderLayer(T patch, E entity, RenderLayer<E, M> vanillaLayer, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                               float bob, float yRot, float xRot, float partialTicks) {
        OpenMatrix4f[] itemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, patch.getArmature(), poses);
        boolean itemScope = YsmWeaponPoseBridge.beginItemLayer(entity, patch, patch.getArmature());
        try {
            super.renderLayer(patch, entity, vanillaLayer, poseStack, buffer, packedLight,
                    itemPoses, bob, yRot, xRot, partialTicks);
        } finally {
            YsmWeaponPoseBridge.endItemLayer(itemScope);
        }
    }
}
