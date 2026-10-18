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
    public static final ForgeConfigSpec SERVER_SPEC;

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
    /** Additional patched layer class names that draw held items. */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_WEAPON_LAYER_CLASSES;
    public static final ForgeConfigSpec.BooleanValue DOWNLOAD_SERVER_MODELS;
    public static final ForgeConfigSpec.IntValue MAX_DOWNLOADED_MODEL_MIB;
    public static final ForgeConfigSpec.BooleanValue MODEL_TRANSFER_ENABLED;
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_MAX_MODEL_MIB;
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_KIB_PER_TICK;
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_MAX_CONCURRENT;

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

        EXTRA_WEAPON_LAYER_CLASSES = builder
                .comment("Additional Epic Fight layer classes that draw held items for maids.",
                        "A listed superclass also covers its subclasses. Ordinary Epic Fight item layers and",
                        "layers registered for a vanilla held-item layer are detected automatically.")
                .defineListAllowEmpty(java.util.List.of("extraWeaponLayerClasses"),
                        () -> java.util.List.of("reascer.wom.world.entity.client.HindablePatchedItemInHandLayer"),
                        entry -> entry instanceof String name && !name.isBlank());

        DOWNLOAD_SERVER_MODELS = builder
                .comment("Download TLM model packs offered by a server running YSM GEO Compat.",
                        "Copies stay in this mod's per-server cache, not in tlm_custom_pack.")
                .define("downloadServerModels", true);
        MAX_DOWNLOADED_MODEL_MIB = builder
                .comment("Maximum compressed size of one downloaded TLM pack in MiB.")
                .defineInRange("maxDownloadedModelSizeMiB", 64, 1, 256);

        builder.pop();

        CLIENT_SPEC = builder.build();

        ForgeConfigSpec.Builder server = new ForgeConfigSpec.Builder();
        server.comment("YSM GEO Compat - Server Model Transfer").push("server");
        MODEL_TRANSFER_ENABLED = server.define("modelTransferEnabled", true);
        MODEL_TRANSFER_MAX_MODEL_MIB = server.defineInRange("maxModelSizeMiB", 64, 1, 256);
        MODEL_TRANSFER_KIB_PER_TICK = server.defineInRange("transferKiBPerTick", 64, 1, 512);
        MODEL_TRANSFER_MAX_CONCURRENT = server.defineInRange("maxConcurrentTransfers", 2, 1, 8);
        server.pop();
        SERVER_SPEC = server.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }
}
