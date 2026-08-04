package com.ysmef.geomodel.event;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.YSMMeshLibrary;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Regenerates the converted Epic Fight base meshes whenever models are
 * reloaded at runtime ("/ysm model reload" or any "ysm ... reload" command,
 * when the YSM mod is installed alongside).
 *
 * The command itself is handled by YSM, so we listen to Forge's CommandEvent
 * and schedule our regeneration on the client (render) thread with a short
 * delay, letting YSM finish its own reload + client sync first.
 */
@Mod.EventBusSubscriber(
        modid = YSMGeoCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class YSMReloadTrigger {

    /** Ticks to wait after the command before regenerating (lets YSM finish first). */
    private static final int REGENERATE_DELAY_TICKS = 40;

    private static volatile int pendingRegenerateTicks = -1;

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        String command = event.getParseResults().getReader().getString();
        if (command == null) {
            return;
        }
        String normalized = command.trim().replaceAll("\\s+", " ");
        if (normalized.equals("ysm model reload")
                || normalized.startsWith("ysm model reload ")
                || normalized.equals("ysm reload")
                || normalized.startsWith("ysm reload ")) {
            pendingRegenerateTicks = REGENERATE_DELAY_TICKS;
            YSMGeoCompat.LOGGER.info("YSM-GEO Compat: detected '{}', scheduling base mesh regeneration", normalized);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int pending = pendingRegenerateTicks;
        if (pending < 0) {
            return;
        }
        if (pending == 0) {
            pendingRegenerateTicks = -1;
            Minecraft.getInstance().execute(() -> {
                try {
                    YSMGeoCompat.LOGGER.info("YSM-GEO Compat: regenerating base meshes after model reload");
                    YSMMeshLibrary.generateAll();
                } catch (Throwable t) {
                    YSMGeoCompat.LOGGER.error("YSM-GEO Compat: base mesh regeneration failed", t);
                }
            });
            return;
        }
        pendingRegenerateTicks = pending - 1;
    }
}
