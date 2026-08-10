package com.ysmef.geomodel.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_CONVERSION;

    /** Async script evaluation for entities other than the local player. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SCRIPT_ASYNC_EVAL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM GEO Compat - Client Configuration").push("client");

        DEBUG_LOG_CONVERSION = builder
                .comment("Log detailed conversion info to console (for debugging)")
                .define("debugLogConversion", false);

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
