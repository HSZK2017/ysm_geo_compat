package com.ysmef.geomodel.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_CONVERSION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM GEO Compat - Client Configuration").push("client");

        DEBUG_LOG_CONVERSION = builder
                .comment("Log detailed conversion info to console (for debugging)")
                .define("debugLogConversion", false);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
