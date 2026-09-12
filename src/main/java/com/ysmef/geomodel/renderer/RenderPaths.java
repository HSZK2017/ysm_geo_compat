package com.ysmef.geomodel.renderer;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.RenderBridgeRegistry;

/**
 * Explicit warm-up of the direct draw paths.
 *
 * {@code gpu.YsmGpuRenderPath} and {@code cpu.YsmCpuRenderPath} register
 * themselves with {@link com.ysmef.geomodel.model.RenderBridgeRegistry} from a
 * static initializer. Because the model package no longer references those
 * classes (that is what breaks the model <-> gpu/cpu package cycle), nothing
 * would otherwise touch them and the registry would stay empty - the direct
 * paths would silently never be used and every mesh would fall back to Epic
 * Fight's compute path. The outcome is logged so that failure mode is visible
 * instead of silent.
 *
 * The skinning shaders are also compiled here rather than lazily on the first
 * mesh draw: compiling a program in the middle of the entity render pass is both
 * a first-draw hitch and, on at least one AMD driver, a hard crash inside
 * {@code glShaderSource} (observed once as an EXCEPTION_ACCESS_VIOLATION in
 * atio6axx.dll). Startup is the safe place for it.
 *
 * Called once from the client setup event (render thread).
 */
public final class RenderPaths {

    private RenderPaths() {}

    public static void init() {
        com.ysmef.geomodel.gpu.YsmGpuRenderPath.warmUp();
        com.ysmef.geomodel.cpu.YsmCpuRenderPath.warmUp();
        boolean gpu = RenderBridgeRegistry.gpu() != null;
        boolean cpu = RenderBridgeRegistry.cpu() != null;
        if (!gpu || !cpu) {
            YSMGeoCompat.LOGGER.error(
                    "YSM-GEO Compat: direct draw path registration incomplete (gpu={}, cpu={}); "
                            + "converted meshes will fall back to Epic Fight's compute path", gpu, cpu);
        } else {
            YSMGeoCompat.LOGGER.info("YSM-GEO Compat: direct draw paths registered (gpu=true, cpu=true)");
        }
        // Eager shader compilation, ahead of any entity draw (see the class doc).
        try {
            boolean boneOk = com.ysmef.geomodel.gpu.YsmBoneSkinShader.ensureCompiled();
            boolean cpuOk = com.ysmef.geomodel.cpu.YsmCpuSkinShader.ensureCompiled();
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: skinning shaders precompiled (gpuPath={}, cpuPath={})", boneOk, cpuOk);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: skinning shader precompilation failed; the paths will retry on first draw", t);
        }
    }
}
