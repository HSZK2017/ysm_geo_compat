package com.ysmef.geomodel.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

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

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
