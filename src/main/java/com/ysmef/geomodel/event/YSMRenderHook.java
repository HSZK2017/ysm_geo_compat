package com.ysmef.geomodel.event;

import com.ysmef.geomodel.YSMGeoCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Render-time bridge between the vanilla player renderer and Epic Fight.
 *
 * Problem: renderer-replacing mods (e.g. YSM, when installed alongside) replace
 * the vanilla player renderer by listening to RenderPlayerEvent.Pre at NORMAL
 * priority and drawing their own renderer. That renderer re-posts a
 * RenderLivingEvent.Pre from inside LivingEntityRenderer.render, which Epic
 * Fight's handler treats as a fresh render request - but with the foreign
 * renderer, which crashes Epic Fight's player-specific cast, and can lead to
 * double rendering.
 *
 * Fix: intercept the vanilla render of players at HIGHEST priority (both
 * renderer-replacing mods' and Epic Fight's handlers run at NORMAL). When Epic
 * Fight will render the player anyway (patch.overrideRender()), the armature
 * render is performed right here through Epic Fight's pipeline and the event is
 * canceled, so neither the vanilla renderer nor any foreign renderer runs for
 * that frame. When Epic Fight does not take over rendering, the event is left
 * untouched and the mod renders normally.
 *
 * Events fired with a non-PlayerRenderer (e.g. a nested event inside a foreign
 * renderer) are left to Epic Fight's own handler.
 */
@Mod.EventBusSubscriber(
        modid = YSMGeoCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class YSMRenderHook {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!(event.getRenderer() instanceof PlayerRenderer)) {
            return;
        }

        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
        if (patch == null || !patch.overrideRender()) {
            return;
        }
        if (ClientEngine.getInstance().isVanillaModelDebuggingMode()) {
            return;
        }

        logTakeoverOnce(player);

        float partialTick = event.getPartialTick();
        boolean guiLikeRender = (partialTick == 0.0F || partialTick == 1.0F);

        if (guiLikeRender && patch instanceof LocalPlayerPatch localPlayerPatch) {
            float originalYRot = localPlayerPatch.getModelYRot();
            localPlayerPatch.setModelYRotInGui(player.getYRot());
            event.getPoseStack().translate(0.0D, 0.1D, 0.0D);
            ClientEngine.getInstance().renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), partialTick);
            event.setCanceled(true);
            localPlayerPatch.disableModelYRotInGui(originalYRot);
        } else {
            ClientEngine.getInstance().renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), partialTick);
            event.setCanceled(true);
        }
    }

    private static final java.util.Set<String> LOGGED_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logTakeoverOnce(Player player) {
        if (LOGGED_PLAYERS.add(player.getGameProfile().getName())) {
            YSMGeoCompat.LOGGER.info("YSM-GEO Compat: taking over player rendering for '{}' via Epic Fight pipeline",
                    player.getGameProfile().getName());
        }
    }
}
