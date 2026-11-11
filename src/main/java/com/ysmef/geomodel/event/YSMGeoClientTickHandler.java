package com.ysmef.geomodel.event;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.TlmModelLibrary;
import com.ysmef.geomodel.model.YSMMeshLibrary;
import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;
import com.ysmef.geomodel.network.TlmPackClient;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Uploads decoded textures before rendering and runs pack sync, delayed releases,
 * cache trimming and mesh prewarming at the end of client ticks.
 */
@Mod.EventBusSubscriber(
        modid = YSMGeoCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class YSMGeoClientTickHandler {

    private YSMGeoClientTickHandler() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            YSMMeshLibrary.processPendingTextureUploads();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // END only: the tick has advanced, so the release delays measured here
        // correspond to whole ticks, and the work runs after the frame's input
        // handling rather than in the middle of it.
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            YSMRuntimeModel.sweepIdleAnimators();
            TlmPackClient.tick();
            YSMMeshLibrary.tick();
            TlmModelLibrary.prewarmMeshes();
        } catch (Throwable t) {
            // A tick handler that throws is logged and re-run every tick, which
            // would flood the log; the deferred work is best-effort anyway.
            YSMGeoCompat.LOGGER.error("YSM-GEO Compat: client tick maintenance failed", t);
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        YSMRuntimeModel.clearAnimators();
        TlmPackClient.clear();
    }
}
