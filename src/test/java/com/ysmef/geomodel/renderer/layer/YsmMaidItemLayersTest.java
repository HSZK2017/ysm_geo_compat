package com.ysmef.geomodel.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"rawtypes", "unchecked"})
class YsmMaidItemLayersTest {
    static class CustomLayer extends PatchedLayer {
        @Override
        protected void renderLayer(LivingEntityPatch patch, LivingEntity entity, RenderLayer layer,
                                   PoseStack stack, MultiBufferSource buffer, int light, OpenMatrix4f[] poses,
                                   float bob, float yRot, float xRot, float partialTicks) {}
    }

    static class SheathLayer extends CustomLayer {}
    static class SpecializedItemLayer extends PatchedItemInHandLayer {}

    @Test
    void wrapsOriginalLayersAndFindsReplacementsAddedAfterConstruction() {
        CustomLayer otherModsItem = new CustomLayer();
        CustomLayer cape = new CustomLayer();
        PatchedItemInHandLayer eftlmItem = new PatchedItemInHandLayer();
        Map<Class<?>, PatchedLayer> patched = new HashMap<>();
        patched.put(ItemInHandLayer.class, otherModsItem);
        patched.put(CapeLayer.class, cape);
        List<PatchedLayer> custom = new ArrayList<>(List.of(eftlmItem));
        Map<PatchedLayer, YsmAnchoredItemLayer> wrappers = new IdentityHashMap<>();

        assertTrue(YsmMaidItemLayers.wrapAll(patched, custom, wrappers, Set.of()));
        assertSame(otherModsItem, assertInstanceOf(YsmAnchoredItemLayer.class,
                patched.get(ItemInHandLayer.class)).delegate());
        assertSame(eftlmItem, assertInstanceOf(YsmAnchoredItemLayer.class, custom.get(0)).delegate());
        assertSame(cape, patched.get(CapeLayer.class));
        assertFalse(YsmMaidItemLayers.wrapAll(patched, custom, wrappers, Set.of()));

        SpecializedItemLayer later = new SpecializedItemLayer();
        custom.set(0, later);
        assertTrue(YsmMaidItemLayers.wrapAll(patched, custom, wrappers, Set.of()));
        assertSame(later, assertInstanceOf(YsmAnchoredItemLayer.class, custom.get(0)).delegate());

        SheathLayer configured = new SheathLayer();
        custom.add(configured);
        assertTrue(YsmMaidItemLayers.wrapAll(patched, custom, wrappers,
                Set.of(CustomLayer.class.getName())));
        assertSame(configured, assertInstanceOf(YsmAnchoredItemLayer.class, custom.get(1)).delegate());
    }
}
