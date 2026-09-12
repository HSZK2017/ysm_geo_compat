package com.ysmef.geomodel.event;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.TlmModelLibrary;
import com.ysmef.geomodel.model.YSMMeshLibrary;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client tick driver for the deferred GL work of the converted maid meshes.
 *
 * Everything the conversion and the draw path cannot do inline is collected
 * here, because there is exactly one place in a frame where it is safe and
 * cheap: between frames, on the render thread.
 *
 * - texture decodes finish on a background pool and their GL uploads are
 *   drained within a per-frame budget ({@link YSMMeshLibrary#tick()})
 * - evicted meshes and textures are destroyed only after a few ticks, so no
 *   draw recorded earlier in the frame can still reference them
 * - the loaded-model cache is trimmed down to the configured cap
 * - freshly generated meshes are instantiated a few per tick, so a maid
 *   entering view does not pay the mesh parse inside a loading frame
 *
 * Registered on the FORGE bus (the game bus), not the mod bus: client ticks are
 * a game event, while {@link YSMCompatClientEvents} handles the mod lifecycle.
 */
@Mod.EventBusSubscriber(
        modid = YSMGeoCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class YSMGeoClientTickHandler {

    private YSMGeoClientTickHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // END only: the tick has advanced, so the release delays measured here
        // correspond to whole ticks, and the work runs after the frame's input
        // handling rather than in the middle of it.
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            YSMMeshLibrary.tick();
            TlmModelLibrary.prewarmMeshes();
        } catch (Throwable t) {
            // A tick handler that throws is logged and re-run every tick, which
            // would flood the log; the deferred work is best-effort anyway.
            YSMGeoCompat.LOGGER.error("YSM-GEO Compat: client tick maintenance failed", t);
        }
    }
}
