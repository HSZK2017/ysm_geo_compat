package com.ysmef.geomodel.mixin.eftlm;

import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;

import java.util.List;
import java.util.Map;

/** Accesses the layers that other mods may replace after EFTLM constructs its renderer. */
@Mixin(value = PatchedLivingEntityRenderer.class, remap = false)
public interface PatchedLivingEntityRendererLayersAccessor {
    @Accessor("patchedLayers")
    Map<Class<?>, PatchedLayer<?, ?, ?, ? extends RenderLayer<?, ?>>> ysmgeo$getPatchedLayers();

    @Accessor("customLayers")
    List<PatchedLayer<?, ?, ?, ? extends RenderLayer<?, ?>>> ysmgeo$getCustomLayers();
}
