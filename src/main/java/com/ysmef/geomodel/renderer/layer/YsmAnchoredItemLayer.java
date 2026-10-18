package com.ysmef.geomodel.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Let the original held-item layer draw with the converted maid model's palm poses. */
public final class YsmAnchoredItemLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends EntityModel<E>>
        extends PatchedLayer<E, T, M, RenderLayer<E, M>> {

    private final PatchedLayer<E, T, M, ? extends RenderLayer<E, M>> delegate;

    public YsmAnchoredItemLayer(PatchedLayer<E, T, M, ? extends RenderLayer<E, M>> delegate) {
        this.delegate = delegate;
    }

    public PatchedLayer<E, T, M, ? extends RenderLayer<E, M>> delegate() {
        return delegate;
    }

    @Override
    protected void renderLayer(T patch, E entity, RenderLayer<E, M> vanillaLayer, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                               float bob, float yRot, float xRot, float partialTicks) {
        OpenMatrix4f[] itemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, patch.getArmature(), poses);
        boolean itemScope = YsmWeaponPoseBridge.beginItemLayer(entity, patch, patch.getArmature());
        try {
            delegate.renderLayer(entity, patch, vanillaLayer, poseStack, buffer, packedLight,
                    itemPoses, bob, yRot, xRot, partialTicks);
        } finally {
            YsmWeaponPoseBridge.endItemLayer(itemScope);
        }
    }
}
