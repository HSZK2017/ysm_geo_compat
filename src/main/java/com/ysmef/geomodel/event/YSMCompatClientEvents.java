package com.ysmef.geomodel.event;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.TlmModelLibrary;
import com.ysmef.geomodel.model.YSMMeshLibrary;
import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side registration:
 * - registers the generated mesh resource pack (converted TLM maid meshes,
 *   see TlmModelLibrary)
 * - triggers the TLM mesh generation at client setup and refreshes it on reload
 */
@Mod.EventBusSubscriber(
        modid = YSMGeoCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class YSMCompatClientEvents {

    /**
     * Register the generated mesh folder as an always-on client resource pack, so
     * Epic Fight's mesh loader can read the generated animmodels JSONs.
     */
    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        YSMMeshLibrary.preparePackFolder();
        event.addRepositorySource((consumer) -> {
            // Pack.create is non-null for these arguments, so it is consumed
            // directly instead of behind a null check that can never be false.
            consumer.accept(Pack.create(
                    "ysm_geo_compat_generated",
                    Component.literal("YSM-GEO Generated Meshes"),
                    true,
                    (id) -> new PathPackResources(id, YSMMeshLibrary.getPackRoot(), true),
                    new Pack.Info(Component.literal("Generated Epic Fight base meshes"), 15, FeatureFlags.VANILLA_SET),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    false,
                    PackSource.BUILT_IN));
        });
    }

    /**
     * Generate the Epic Fight base meshes for all locally available TLM model
     * pack maid models (tlm_custom_pack + jar-builtin maid_model.json).
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Load the direct draw paths so they self-register (see RenderPaths).
            com.ysmef.geomodel.renderer.RenderPaths.init();
            try {
                TlmModelLibrary.resetLazyGeneration();
                TlmModelLibrary.generateAll(Minecraft.getInstance().getResourceManager());
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.error("YSM-GEO Compat: TLM mesh generation failed", t);
            }
        });
    }

    /**
     * Refresh generated meshes on resource reload so model file changes (F3+T) apply.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            YSMRuntimeModel.invalidateAll();
            YSMRuntimeModel.clearAnimators();
            // Drop every re-bound bind armature and captured pose of the model-part
            // pose correction (YsmBindArmature): the meshes are regenerated below
            // and the old instances are dropped, so the cached per-model armatures
            // would otherwise keep the stale geometry-derived pivots alive.
            com.ysmef.geomodel.model.runtime.YsmBindArmature.invalidateAll();
            // Destroy evicted meshes that are no longer owned by Epic Fight,
            // revoke old texture tasks and release dynamic textures first.
            YSMMeshLibrary.invalidateAll();
            // Then free the CPU/GPU paths' resources for all remaining meshes.
            YSMMeshLibrary.disposeAllPaths();
            // Per-player once-log state (renderer.YSMMeshSelector) is dropped too,
            // so diagnostics stay useful after a reload.
            com.ysmef.geomodel.renderer.YSMMeshSelector.clear();
            try {
                TlmModelLibrary.resetLazyGeneration();
                TlmModelLibrary.generateAll(resourceManager);
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.error("YSM-GEO Compat: TLM mesh regeneration failed", t);
            }
        });
    }
}
