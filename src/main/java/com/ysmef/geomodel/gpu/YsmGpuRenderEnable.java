package com.ysmef.geomodel.gpu;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.config.YSMCompatConfig;

/**
 * Gates this mod's GPU skinning path (ported from the main project's
 * YsmGpuRenderEnable, simplified for this mod).
 *
 * The main project links its GPU toggle to ModernYSM's own renderer toggles
 * because it renders YSM player models - the same models ModernYSM renders.
 * This mod renders only Touhou Little Maid GEO model-pack meshes and never
 * touches YSM models (those are yielded to the YSM_EpicFight_Compat mod when
 * installed), so the GPU path simply follows the mod's own client config
 * (enableGpuRender), mirroring ModernYSM's UseGpuRenderer toggle. Like
 * ModernYSM and the main project, the toggle is auto-disabled when the GPU
 * path proves unavailable at runtime (see disableIfOwned).
 */
public final class YsmGpuRenderEnable {

    private YsmGpuRenderEnable() {}

    /** Whether the compat GPU skinning path may render right now. */
    public static boolean isEnabled() {
        return YSMCompatConfig.ENABLE_GPU_RENDER.get();
    }

    /**
     * Auto-disable the GPU toggle when the GPU path is unavailable, mirroring
     * ModernYSM's behavior (it calls USE_GPU_RENDERER.set(false) when the GPU
     * capability check fails) and the main project's disableIfOwned.
     */
    public static void disableIfOwned() {
        try {
            if (YSMCompatConfig.ENABLE_GPU_RENDER.get()) {
                YSMCompatConfig.ENABLE_GPU_RENDER.set(false);
                YSMGeoCompat.LOGGER.info(
                        "YSM-GEO Compat: GPU skinning path unavailable, disabled 'enableGpuRender' (mirrors ModernYSM's auto-disable)");
            }
        } catch (Throwable ignored) {
        }
    }
}
