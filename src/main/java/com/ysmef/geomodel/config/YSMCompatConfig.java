package com.ysmef.geomodel.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

    /**
     * Diagnostic logging switch ({@code -Dysm_geo_compat.diag=true}): per-draw
     * skip reasons, GPU input dumps and timing traces.
     *
     * Read once into a constant: it is consulted from the per-draw skip paths,
     * where {@code System.getProperty} would be a Hashtable lookup plus a
     * security check on every skipped draw.
     */
    public static final boolean DIAG = Boolean.getBoolean("ysm_geo_compat.diag");

    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * ModernYSM-style GPU skinning: draw converted TLM meshes with a bone SSBO
     * + custom skinning shader (one glDrawArrays per model, vertex skinning on
     * the GPU), instead of Epic Fight's per-frame compute dispatch. Falls back
     * to Epic Fight's compute path automatically when unavailable.
     */
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU_RENDER;

    /** Async script evaluation for entities other than the local player. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SCRIPT_ASYNC_EVAL;

    /**
     * Maximum number of converted maid models kept resident at once. Models
     * beyond the cap release their GL resources (mesh buffers, textures) least
     * recently used first; Epic Fight re-loads an evicted model from the
     * generated mesh JSON on its next draw, so nothing is re-converted.
     */
    public static final ForgeConfigSpec.IntValue LAZY_MODEL_CACHE_SIZE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM GEO Compat - Client Configuration").push("client");

        ENABLE_GPU_RENDER = builder
                .comment("Render TLM meshes with the GPU skinning path (bone SSBO + skinning shader, ported from ModernYSM/OpenYSM).",
                        "Like ModernYSM's UseGpuRenderer toggle, this option is auto-disabled when the GPU path is",
                        "unavailable at runtime (OpenGL below 4.3 / OpenGL ES below 3.1, shader compile failure, ...).",
                        "The Epic Fight compute-shader path is used as the fallback automatically.")
                .define("enableGpuRender", true);

        ENABLE_SCRIPT_ASYNC_EVAL = builder
                .comment("Evaluate molang scripts of entities other than the local player on a background thread (double-buffered), keeping the render thread free")
                .define("scriptAsyncEval", true);

        LAZY_MODEL_CACHE_SIZE = builder
                .comment("Maximum number of converted maid models kept loaded in memory (LRU cache).",
                        "Models beyond this limit are evicted least recently used first: their mesh buffers and",
                        "textures are released, and Epic Fight re-loads the model from the generated mesh JSON the",
                        "next time it is drawn (no re-conversion, nothing is permanently lost).",
                        "Lower values save VRAM/RAM at the cost of a short re-load when a model becomes visible again.",
                        "A converted model costs roughly 0.1-1 MB of vertex data, so the default covers every model",
                        "a normal session can meet at once; raise it to keep whole large packs resident.")
                .defineInRange("lazyModelCacheSize", 64, 8, 512);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
