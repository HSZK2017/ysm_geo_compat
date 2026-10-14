package com.ysmef.geomodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.YSMGeoModel;
import com.ysmef.geomodel.ysm.script.ScriptAnim;
import com.ysmef.geomodel.ysm.script.ScriptJson;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import yesman.epicfight.api.client.model.Meshes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of converted Epic Fight base meshes for Touhou Little Maid GEO models.
 *
 * TLM loads model packs from the tlm_custom_pack folder by direct file IO (not
 * the MC ResourceManager). This class replicates that: it walks every folder
 * pack under tlm_custom_pack, reads maid_model.json + model JSONs + texture PNGs
 * from disk, converts them to EF mesh JSONs, and registers textures. Jar-builtin
 * TLM models (inside the TLM mod jar) are additionally fetched via the
 * ResourceManager.
 */
public final class TlmModelLibrary {

    private static final Path MESH_DIR = YSMMeshLibrary.getMeshDir().resolve("tlm");

    public record TlmMeshEntry(String meshOwnerId, Meshes.MeshAccessor<YSMMesh> accessor,
                               ResourceLocation texture) {}

    private static final Map<String, TlmMeshEntry> MESHES = new LinkedHashMap<>();

    private TlmModelLibrary() {}

    /**
     * EpicFight_TouhouLittleMaid applies its own 0.8 render scale to EVERY maid
     * it renders (hardcoded in MaidPatch#getModelMatrix). The baked scale is
     * chosen so the final on-screen size matches the model pack's intent:
     * - EFTLM ships a built-in mesh for the model (WineFox family): EFTLM's
     *   tuned mesh takes over and our baked scale is dropped to 1.0
     * - otherwise our converted mesh is rendered under EFTLM's 0.8, so the
     *   pack's render_entity_scale is baked divided by 0.8 to cancel it
     * - without EFTLM the pack scale is baked as-is
     */
    private static final float EFTLM_RENDER_SCALE = 0.8F;

    /** Accepted range of a pack's declared render_entity_scale (guards absurd values). */
    private static final float MIN_PACK_SCALE = 0.2F;
    private static final float MAX_PACK_SCALE = 2.0F;

    /** Log a scale computation per model entry only when conversion logging is on. */
    private static volatile boolean verboseScaleLogged = false;

    private static volatile Boolean eftlmLoaded = null;

    private static boolean isEftlmLoaded() {
        Boolean loaded = eftlmLoaded;
        if (loaded == null) {
            boolean result = false;
            try {
                result = net.minecraftforge.fml.ModList.get() != null
                        && net.minecraftforge.fml.ModList.get().isLoaded("ef_tlm");
            } catch (Throwable ignored) {
            }
            eftlmLoaded = result;
            return result;
        }
        return loaded;
    }

    private static float maidScaleOf(JsonObject entry, ResourceManager resourceManager) {
        boolean eftlm = isEftlmLoaded();
        String modelId = entry.has("model_id") ? entry.get("model_id").getAsString() : "?";
        boolean hasBuiltin = eftlm && eftlmHasBuiltinMesh(entry, resourceManager);
        boolean coveredOverride = isEftlmCovered(modelId);
        float packScale = entry.has("render_entity_scale")
                ? Math.max(MIN_PACK_SCALE, Math.min(MAX_PACK_SCALE, entry.get("render_entity_scale").getAsFloat()))
                : 1.0f;
        float scale;
        if (eftlm && (hasBuiltin || coveredOverride)) {
            scale = 1.0F;
        } else if (eftlm) {
            scale = packScale / EFTLM_RENDER_SCALE;
        } else {
            scale = packScale;
        }
        float finalScale = eftlm ? scale * EFTLM_RENDER_SCALE : scale;
        // Per-entry detail only once (the full table is useful when diagnosing a
        // scale problem, but 235 INFO lines per generation is startup spam).
        if (!verboseScaleLogged) {
            verboseScaleLogged = true;
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: maid scale '{}': eftlmLoaded={}, eftlmBuiltinMesh={}, eftlmCoveredOverride={}, packScale={}, bakedScale={}, finalRenderScale={}",
                    modelId, eftlm, hasBuiltin, coveredOverride, packScale, scale, finalScale);
        }
        return scale;
    }

    /**
     * Model ids treated as covered by EFTLM's built-in meshes even though EFTLM
     * registers no dedicated mesh for them: EFTLM's getMeshProvider then falls
     * back to its default WineFox mesh, and this mod must not substitute its
     * converted mesh for them.
     */
    private static final Set<String> EFTLM_COVERED_IDS = Set.of("winefox_blue");

    /**
     * Whether the given model id (with or without namespace) is covered by
     * EFTLM's built-in meshes, either directly or via the override list.
     */
    public static boolean isEftlmCovered(String modelId) {
        if (modelId == null) {
            return false;
        }
        int colon = modelId.indexOf(':');
        String stripped = colon >= 0 ? modelId.substring(colon + 1) : modelId;
        return EFTLM_COVERED_IDS.contains(stripped);
    }

    /**
     * Whether EFTLM ships a built-in mesh for this model: EFTLM's registry
     * (EFTLM_Meshes) is keyed by the model id with the namespace stripped
     * (MaidPatch#getModelID) and loaded from its animmodels/entity/*.json
     * files, so the same file lookup is performed against the resource
     * manager (independent of EFTLM's own reload timing).
     */
    private static boolean eftlmHasBuiltinMesh(JsonObject entry, ResourceManager resourceManager) {
        if (!entry.has("model_id")) {
            return false;
        }
        String modelId = entry.get("model_id").getAsString();
        int colon = modelId.indexOf(':');
        String stripped = colon >= 0 ? modelId.substring(colon + 1) : modelId;
        try {
            return resourceManager.getResource(
                    ResourceLocation.fromNamespaceAndPath("ef_tlm", "animmodels/entity/" + stripped + ".json")).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    public static TlmMeshEntry find(String modelId) {
        TlmMeshEntry entry = MESHES.get(modelId);
        if (entry != null) {
            // LRU touch: a model that is actually being drawn stays resident, so
            // the cache evicts the models the player has moved away from.
            YSMMeshLibrary.touchModel(entry.meshOwnerId());
        }
        return entry;
    }

    /** The mesh accessor registered for a model, or null (used by the LRU eviction). */
    static Meshes.MeshAccessor<YSMMesh> accessorOf(String modelId) {
        TlmMeshEntry entry = MESHES.get(modelId);
        return entry != null ? entry.accessor() : null;
    }

    /** Extra-texture variants share their base model's mesh accessor and cache slot. */
    static String meshOwnerOf(String modelId) {
        TlmMeshEntry entry = MESHES.get(modelId);
        return entry != null ? entry.meshOwnerId() : modelId;
    }

    /** Restore all texture ownership after an evicted shared mesh is loaded again. */
    static void bindTexturesOfMesh(String meshOwnerId) {
        for (TlmMeshEntry entry : MESHES.values()) {
            if (meshOwnerId.equals(entry.meshOwnerId())) {
                YSMMeshLibrary.bindModelTexture(meshOwnerId, entry.texture());
            }
        }
    }

    static void invalidateRuntimeModelsOfMesh(String meshOwnerId) {
        for (Map.Entry<String, TlmMeshEntry> entry : MESHES.entrySet()) {
            if (meshOwnerId.equals(entry.getValue().meshOwnerId())) {
                com.ysmef.geomodel.model.runtime.YSMRuntimeModel.invalidate(entry.getKey());
            }
        }
    }

    /**
     * Models whose mesh has not been instantiated yet. Filled by
     * {@link #generateAll} and drained by {@link #prewarmMeshes}; only touched
     * from the render thread, so a plain LinkedHashSet keeps the order stable.
     */
    private static final java.util.LinkedHashSet<String> PENDING_PREWARM = new java.util.LinkedHashSet<>();

    /**
     * Per-tick budget for the mesh prewarm.
     *
     * A converted mesh is only parsed and built when something first asks for it,
     * and that first ask is a maid entering view - the worst possible moment for
     * a multi-millisecond parse, because it happens inside a frame that is
     * already loading chunks. Building a few models per tick right after login
     * moves that cost off the critical path entirely; a failed model is dropped
     * from the queue and retried by the normal first-draw path.
     */
    private static final long PREWARM_BUDGET_NANOS = 8_000_000L;

    /** Meshes instantiated since the last {@link #generateAll} (for the summary line). */
    private static int prewarmedThisGeneration;

    /**
     * Instantiate recently registered meshes on the render thread within a small
     * per-tick time budget (called from the client tick). Does nothing until a
     * world is loaded: Epic Fight's mesh loader reads through the resource
     * manager that its own resource reload installs, which only exists once the
     * client is past the title screen.
     */
    public static void prewarmMeshes() {
        if (PENDING_PREWARM.isEmpty()) {
            return;
        }
        if (net.minecraft.client.Minecraft.getInstance().level == null) {
            return;
        }
        if (YSMMeshLibrary.loadedModelCount() >= YSMMeshLibrary.modelCacheCap()) {
            // The cache is already full: instantiating more models here would only
            // make the trim evict them again, so the queue is dropped and every
            // later model is loaded on demand (self-healing, see trimIfNeeded).
            reportPrewarm();
            PENDING_PREWARM.clear();
            return;
        }
        long deadline = System.nanoTime() + PREWARM_BUDGET_NANOS;
        java.util.Iterator<String> it = PENDING_PREWARM.iterator();
        while (it.hasNext()) {
            String modelId = it.next();
            it.remove();
            TlmMeshEntry entry = MESHES.get(modelId);
            if (entry == null) {
                continue;
            }
            try {
                // An evicted model is re-loaded here for free: Epic Fight rebuilds
                // the mesh from the generated JSON when its cache entry is gone.
                entry.accessor().get();
                YSMMeshLibrary.markMeshLoaded(modelId);
                prewarmedThisGeneration++;
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: failed to prewarm mesh for '{}'", modelId, t);
            }
            if (System.nanoTime() > deadline) {
                return;
            }
        }
        reportPrewarm();
    }

    /**
     * Report the finished prewarm once per mesh generation. The line is the only
     * trace that the mesh build cost moved off the first draw of each maid, so it
     * is worth one log entry; the count is reset by the next {@link #generateAll}.
     */
    private static void reportPrewarm() {
        int count = prewarmedThisGeneration;
        prewarmedThisGeneration = 0;
        if (count > 0) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: prewarmed {} maid meshes on the client tick", count);
        }
    }

    private static volatile boolean attemptedLazyGeneration = false;

    public static void ensureGenerated() {
        if (!MESHES.isEmpty() || attemptedLazyGeneration) {
            return;
        }
        attemptedLazyGeneration = true;
        try {
            generateAll(net.minecraft.client.Minecraft.getInstance().getResourceManager());
        } catch (Throwable t) {
            // Reset the guard so the next call retries: leaving it set with an
            // empty/partial map silently disabled every maid mesh until F3+T.
            attemptedLazyGeneration = false;
            YSMGeoCompat.LOGGER.error("YSM-GEO Compat: lazy TLM mesh generation failed", t);
        }
    }

    public static void resetLazyGeneration() {
        attemptedLazyGeneration = false;
    }

    /**
     * Cap on concurrent mesh conversions.
     *
     * A conversion holds the parsed geometry, the EF mesh JSON and the compiled
     * SkinnedMesh at once (hundreds of MB for the large packs), so the full
     * rebuild is run through a small semaphore even when several loaders ask for
     * it at the same time (client setup + a resource reload).
     */
    private static final java.util.concurrent.Semaphore CONVERSION_SLOTS =
            new java.util.concurrent.Semaphore(2);

    /**
     * Build the mesh registry for every locally available TLM model pack.
     *
     * Must run on the render thread: {@code Meshes.MeshAccessor.create} writes
     * Epic Fight's static {@code Meshes.ACCESSORS} HashMap, which is not
     * thread-safe, and the mesh JSONs are read through Epic Fight's asset loader.
     */
    public static void generateAll(ResourceManager resourceManager) {
        // Assert rather than silently corrupt Epic Fight's static mesh table when
        // a future caller moves this onto a worker.
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException(
                    "TlmModelLibrary.generateAll must run on the render thread "
                            + "(Epic Fight's Meshes.ACCESSORS is not thread-safe)");
        }
        boolean acquired = false;
        try {
            CONVERSION_SLOTS.acquire();
            acquired = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a TLM conversion slot", e);
        }
        try {
            generateAllLocked(resourceManager);
        } finally {
            if (acquired) {
                CONVERSION_SLOTS.release();
            }
        }
    }

    private static synchronized void generateAllLocked(ResourceManager resourceManager) {
        // Build into a local map and swap it in at the end: a throw halfway
        // through (a malformed pack, an I/O error) used to leave MESHES cleared
        // and only partly refilled, so maids silently lost their meshes until the
        // next reload.
        Map<String, TlmMeshEntry> built = new LinkedHashMap<>();
        int[] converted = {0};
        int[] seen = {0};

        // 1) tlm_custom_pack folder packs — direct disk IO (mirrors TLM's CustomPackLoader)
        Path packFolder = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("tlm_custom_pack");
        if (Files.isDirectory(packFolder)) {
            try (var stream = Files.walk(packFolder, 1)) {
                stream.filter(Files::isDirectory).skip(1)
                        .filter(pack -> PackFileIO.isInsideReal(pack, packFolder))
                        .forEach(pack -> scanDiskPack(pack, built, converted, seen));
            } catch (Exception e) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to scan tlm_custom_pack: {}", e.toString());
            }
        }

        // 2) jar-builtin manifests (e.g. touhou_little_maid:maid_model.json inside the TLM jar)
        for (String namespace : resourceManager.getNamespaces()) {
            ResourceLocation manifestId = ResourceLocation.fromNamespaceAndPath(namespace, "maid_model.json");
            for (Resource resource : resourceManager.getResourceStack(manifestId)) {
                try (InputStream in = resource.open()) {
                    JsonObject packJson = JsonParser.parseString(PackFileIO.readString(in)).getAsJsonObject();
                    JsonArray modelList = packJson.getAsJsonArray("model_list");
                    if (modelList == null) continue;
                    for (JsonElement element : modelList) {
                        if (!element.isJsonObject()) continue;
                        seen[0]++;
                        try {
                            if (convertEntryFromRM(resourceManager, element.getAsJsonObject(), built)) {
                                converted[0]++;
                            }
                        } catch (Exception e) {
                            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to convert TLM maid model: {}", e.toString());
                        }
                    }
                } catch (Exception e) {
                    // Not a hard failure for every namespace (most have no
                    // maid_model.json), but a malformed one must be visible.
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read {}: {}", manifestId, e.toString());
                }
            }
        }

        MESHES.clear();
        MESHES.putAll(built);
        // Queue every freshly registered model for the tick-driven prewarm, so the
        // first draw of a maid does not pay the mesh parse (see prewarmMeshes).
        PENDING_PREWARM.clear();
        for (TlmMeshEntry entry : built.values()) {
            PENDING_PREWARM.add(entry.meshOwnerId());
        }
        // Drop generated textures no current model refers to (removed packs,
        // renamed models, and the doubled-extension files of earlier versions).
        YSMMeshLibrary.deleteStalePackTextures();
        YSMGeoCompat.LOGGER.info("YSM-GEO Compat: generated {} TLM maid meshes from {} model entries", converted[0], seen[0]);
    }

    private static void scanDiskPack(Path packRoot, Map<String, TlmMeshEntry> built, int[] converted, int[] seen) {
        Path assetsDir = packRoot.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return;
        try (var nsStream = Files.list(assetsDir)) {
            nsStream.filter(Files::isDirectory).forEach(nsDir -> {
                Path manifest = resolveInPackAssets(packRoot, nsDir.getFileName().toString(), "maid_model.json");
                if (manifest == null) return;
                try {
                    JsonObject packJson = JsonParser.parseString(
                            PackFileIO.readString(manifest)).getAsJsonObject();
                    JsonArray modelList = packJson.getAsJsonArray("model_list");
                    if (modelList == null) return;
                    for (JsonElement element : modelList) {
                        if (!element.isJsonObject()) continue;
                        seen[0]++;
                        try {
                            if (convertEntryFromDisk(packRoot, element.getAsJsonObject(), built)) {
                                converted[0]++;
                            }
                        } catch (Exception e) {
                            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to convert TLM maid model: {}", e.toString());
                        }
                    }
                } catch (Exception e) {
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read {}: {}", manifest, e.toString());
                }
            });
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to scan {}: {}", assetsDir, e.toString());
        }
    }

    /** Convert a model entry, reading model/texture files directly from disk (packRoot/assets/...). */
    private static boolean convertEntryFromDisk(Path packRoot, JsonObject entry, Map<String, TlmMeshEntry> built) {
        if (!entry.has("model_id")) return false;
        String modelId = entry.get("model_id").getAsString();
        ResourceLocation modelIdRl = safeResourceLocation(modelId);
        if (modelIdRl == null) {
            logRejectedPath("model_id", modelId);
            return false;
        }

        String namespace = modelIdRl.getNamespace();
        boolean isGecko = entry.has("is_gecko") && entry.get("is_gecko").getAsBoolean();
        float scale = maidScaleOf(entry, net.minecraft.client.Minecraft.getInstance().getResourceManager());

        // resolve model file path relative to packRoot/assets/<ns>/<path>
        ResourceLocation modelRl = entry.has("model")
                ? safeResourceLocation(entry.get("model").getAsString())
                : ResourceLocation.fromNamespaceAndPath(namespace, "models/entity/" + modelIdRl.getPath() + ".json");
        if (modelRl == null) {
            logRejectedPath("model", entry.get("model").getAsString());
            return false;
        }
        Path modelFile = resolveInPackAssets(packRoot, modelRl);
        if (modelFile == null) return false;

        YSMGeoModel geoModel;
        try {
            String json = PackFileIO.readString(modelFile);
            geoModel = isGecko ? YSMGeoModel.parse(json) : TlmGeoModelParser.parse(json);
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to parse TLM model {} ({}): {}", modelId, modelFile, e.toString());
            return false;
        }
        if (geoModel == null) return false;

        Map<String, ScriptAnim> scriptAnims = loadScriptAnimsFromDisk(packRoot, namespace, entry);

        // resolve texture file
        ResourceLocation ourTexture = null;
        ResourceLocation texRl = entry.has("texture")
                ? safeResourceLocation(entry.get("texture").getAsString())
                : ResourceLocation.fromNamespaceAndPath(namespace, "textures/entity/" + modelIdRl.getPath() + ".png");
        if (texRl == null) {
            logRejectedPath("texture", entry.get("texture").getAsString());
        } else {
            Path texFile = resolveInPackAssets(packRoot, texRl);
            if (texFile != null) {
                try {
                    ourTexture = YSMMeshLibrary.registerTextureBytes(
                            "tlm/" + texRl.getNamespace() + "/" + texRl.getPath(),
                            PackFileIO.readBytes(texFile));
                } catch (Exception e) {
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read TLM texture {}: {}", texFile, e.toString());
                }
            }
        }

        boolean showBackpack = !entry.has("show_backpack") || entry.get("show_backpack").getAsBoolean();

        // extra textures become model variants (model_id + "_" + md5(texturePath)),
        // the same ids TLM hands out through EntityMaid#getModelId
        Map<String, ResourceLocation> extraTextures = new LinkedHashMap<>();
        if (entry.has("extra_textures") && entry.get("extra_textures").isJsonArray()) {
            for (JsonElement elem : entry.getAsJsonArray("extra_textures")) {
                ResourceLocation extraRl = safeResourceLocation(elem.getAsString());
                if (extraRl == null) {
                    logRejectedPath("extra_textures", elem.getAsString());
                    continue;
                }
                Path extraFile = resolveInPackAssets(packRoot, extraRl);
                if (extraFile == null) {
                    continue;
                }
                try {
                    ResourceLocation registered = YSMMeshLibrary.registerTextureBytes(
                            "tlm/" + extraRl.getNamespace() + "/" + extraRl.getPath() + "_" + variantHash(extraRl),
                            PackFileIO.readBytes(extraFile));
                    extraTextures.put(modelId + "_" + variantHash(extraRl), registered);
                } catch (Exception e) {
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read TLM extra texture {}: {}", extraRl, e.toString());
                }
            }
        }

        return writeAndRegister(modelId, modelIdRl, geoModel, scale, ourTexture, scriptAnims, showBackpack, extraTextures, built);
    }

    /** Convert a model entry, reading model/texture files via the resource manager (jar-builtin). */
    private static boolean convertEntryFromRM(ResourceManager resourceManager, JsonObject entry, Map<String, TlmMeshEntry> built) {
        if (!entry.has("model_id")) return false;
        String modelId = entry.get("model_id").getAsString();
        ResourceLocation modelIdRl = safeResourceLocation(modelId);
        if (modelIdRl == null) {
            logRejectedPath("model_id", modelId);
            return false;
        }

        ResourceLocation modelRl = entry.has("model")
                ? safeResourceLocation(entry.get("model").getAsString())
                : ResourceLocation.fromNamespaceAndPath(modelIdRl.getNamespace(),
                        "models/entity/" + modelIdRl.getPath() + ".json");
        if (modelRl == null) {
            logRejectedPath("model", entry.get("model").getAsString());
            return false;
        }
        ResourceLocation textureRl = entry.has("texture")
                ? safeResourceLocation(entry.get("texture").getAsString())
                : ResourceLocation.fromNamespaceAndPath(modelIdRl.getNamespace(),
                        "textures/entity/" + modelIdRl.getPath() + ".png");
        if (textureRl == null) {
            logRejectedPath("texture", entry.get("texture").getAsString());
        }
        boolean isGecko = entry.has("is_gecko") && entry.get("is_gecko").getAsBoolean();
        float scale = maidScaleOf(entry, resourceManager);

        Optional<Resource> modelResource = resourceManager.getResource(modelRl);
        if (modelResource.isEmpty()) return false;

        YSMGeoModel geoModel;
        try (InputStream in = modelResource.get().open()) {
            String json = PackFileIO.readString(in);
            geoModel = isGecko ? YSMGeoModel.parse(json) : TlmGeoModelParser.parse(json);
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to parse TLM model {} ({}): {}", modelId, modelRl, e.toString());
            return false;
        }
        if (geoModel == null) return false;

        Map<String, ScriptAnim> scriptAnims = loadScriptAnimsFromRM(resourceManager, entry);

        ResourceLocation ourTexture = null;
        if (textureRl != null) {
            Optional<Resource> textureResource = resourceManager.getResource(textureRl);
            if (textureResource.isPresent()) {
                try (InputStream in = textureResource.get().open()) {
                    ourTexture = YSMMeshLibrary.registerTextureBytes(
                            "tlm/" + textureRl.getNamespace() + "/" + textureRl.getPath(),
                            PackFileIO.readBytes(in));
                } catch (Exception e) {
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read TLM texture {}: {}", textureRl, e.toString());
                }
            }
        }

        boolean showBackpack = !entry.has("show_backpack") || entry.get("show_backpack").getAsBoolean();

        Map<String, ResourceLocation> extraTextures = new LinkedHashMap<>();
        if (entry.has("extra_textures") && entry.get("extra_textures").isJsonArray()) {
            for (JsonElement elem : entry.getAsJsonArray("extra_textures")) {
                ResourceLocation texRl = safeResourceLocation(elem.getAsString());
                if (texRl == null) {
                    logRejectedPath("extra_textures", elem.getAsString());
                    continue;
                }
                Optional<Resource> extraResource = resourceManager.getResource(texRl);
                if (extraResource.isEmpty()) {
                    continue;
                }
                try (InputStream in = extraResource.get().open()) {
                    ResourceLocation registered = YSMMeshLibrary.registerTextureBytes(
                            "tlm/" + texRl.getNamespace() + "/" + texRl.getPath() + "_" + variantHash(texRl),
                            PackFileIO.readBytes(in));
                    extraTextures.put(modelId + "_" + variantHash(texRl), registered);
                } catch (Exception e) {
                    YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to read TLM extra texture {}: {}", texRl, e.toString());
                }
            }
        }

        return writeAndRegister(modelId, modelIdRl, geoModel, scale, ourTexture, scriptAnims, showBackpack, extraTextures, built);
    }

    /**
     * The md5(texture path) suffix TLM appends to a model id for every
     * extra_textures variant (see TLM's CustomModelPack#decorate).
     */
    private static String variantHash(ResourceLocation textureRl) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(textureRl.getPath().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(textureRl.getPath().hashCode());
        }
    }

    private static boolean writeAndRegister(String modelId, ResourceLocation modelIdRl,
                                            YSMGeoModel geoModel, float scale, ResourceLocation ourTexture,
                                            Map<String, ScriptAnim> scriptAnims, boolean showBackpack,
                                            Map<String, ResourceLocation> extraTextures, Map<String, TlmMeshEntry> built) {
        String meshFile = sanitize(modelIdRl.getNamespace()) + "__" + sanitize(modelIdRl.getPath());
        // sanitize() already yields a traversal-free relative path; the fence
        // re-checks it so no future edit to the naming scheme can turn this into
        // an escape from the generated pack.
        Path outFile = YSMMeshLibrary.resolveInside(MESH_DIR, meshFile + ".json");
        if (outFile == null) {
            logRejectedPath("generated mesh path", meshFile);
            return false;
        }
        String texturePath = ourTexture != null
                ? ourTexture.toString()
                : ResourceLocation.withDefaultNamespace("textures/entity/steve.png").toString();
        try {
            int quads = EFMeshJsonWriter.writeTlmMesh(geoModel, scale, outFile, texturePath);
            if (quads < 0) return false;
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to write TLM mesh for {}: {}", modelId, e.toString());
            return false;
        }
        // Always write the runtime JSON - even when the model ships no
        // animations: the bone table (hierarchy + EF joint binding) is what
        // YsmBindArmature's pose correction reads to compute the per-joint
        // pivots, and models without animation entries (the built-in Touhou
        // Q-style maids have an empty "animation" list) would otherwise get no
        // bind armature at all and keep Epic Fight's Steve-proportion pivots
        // (head/elbow/knee positions completely wrong for their tiny build).
        try {
            EFMeshJsonWriter.writeRuntimeJson(geoModel, scriptAnims, YSMMeshLibrary.getRuntimeFile("tlm/" + meshFile), showBackpack);
            // compile the freshly written runtime JSON off-thread so the first
            // maid draw finds the compiled scripts instead of compiling inline
            YSMMeshLibrary.preloadRuntimeAsync(modelId);
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to write TLM runtime for {}: {}", modelId, e.toString());
        }

        Meshes.MeshAccessor<YSMMesh> accessor = Meshes.MeshAccessor.create(
                YSMGeoCompat.MODID, "entity/tlm/" + meshFile,
                (loader) -> loader.loadSkinnedMesh(YSMMesh::new));
        built.put(modelId, new TlmMeshEntry(modelId, accessor, ourTexture));
        YSMMeshLibrary.bindModelTexture(modelId, ourTexture);
        for (Map.Entry<String, ResourceLocation> variant : extraTextures.entrySet()) {
            built.put(variant.getKey(), new TlmMeshEntry(modelId, accessor, variant.getValue()));
            YSMMeshLibrary.bindModelTexture(modelId, variant.getValue());
        }
        return true;
    }

    /**
     * Loads the model entry's bedrock animation files (TLM model packs animate their
     * models with the same bedrock animation JSON format as YSM) and keeps only the
     * animations relevant to the Epic Fight compat runtime (see ScriptJson).
     * The molang variants driven by these scripts (low-HP forms, magic circles,
     * held-item props) then collapse correctly in battle mode and animate in idle
     * mode instead of rendering all at once.
     */
    private static Map<String, ScriptAnim> loadScriptAnimsFromDisk(Path packRoot, String namespace, JsonObject entry) {
        Map<String, ScriptAnim> out = new LinkedHashMap<>();
        JsonArray animations = entry.has("animation") && entry.get("animation").isJsonArray()
                ? entry.getAsJsonArray("animation") : null;
        if (animations == null) {
            return out;
        }
        for (JsonElement animElem : animations) {
            ResourceLocation animRl = safeResourceLocation(animElem.getAsString());
            if (animRl == null) {
                logRejectedPath("animation", animElem.getAsString());
                continue;
            }
            Path animFile = resolveInPackAssets(packRoot, animRl);
            if (animFile == null) {
                continue;
            }
            try {
                mergeScriptAnims(JsonParser.parseString(PackFileIO.readString(animFile)).getAsJsonObject(), out);
            } catch (Exception e) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to parse TLM animation {}: {}", animRl, e.toString());
            }
        }
        return out;
    }

    private static Map<String, ScriptAnim> loadScriptAnimsFromRM(ResourceManager resourceManager, JsonObject entry) {
        Map<String, ScriptAnim> out = new LinkedHashMap<>();
        JsonArray animations = entry.has("animation") && entry.get("animation").isJsonArray()
                ? entry.getAsJsonArray("animation") : null;
        if (animations == null) {
            return out;
        }
        for (JsonElement animElem : animations) {
            ResourceLocation animRl = safeResourceLocation(animElem.getAsString());
            if (animRl == null) {
                logRejectedPath("animation", animElem.getAsString());
                continue;
            }
            Optional<Resource> resource = resourceManager.getResource(animRl);
            if (resource.isEmpty()) {
                continue;
            }
            try (InputStream in = resource.get().open()) {
                String json = PackFileIO.readString(in);
                mergeScriptAnims(JsonParser.parseString(json).getAsJsonObject(), out);
            } catch (Exception e) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to parse TLM animation {}: {}", animRl, e.toString());
            }
        }
        return out;
    }

    /** Merges all runtime-relevant animations of one bedrock animation file into the output map. */
    private static void mergeScriptAnims(JsonObject root, Map<String, ScriptAnim> out) {
        if (!root.has("animations") || !root.get("animations").isJsonObject()) {
            return;
        }
        JsonObject anims = root.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
            if (ScriptJson.isRuntimeRelevant(entry.getKey()) && entry.getValue().isJsonObject()) {
                out.put(entry.getKey(), ScriptJson.fromBedrock(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
        }
    }

    /**
     * The single shared id/name sanitizer (see YSMMeshLibrary#sanitize). It is
     * deliberately the SAME implementation the runtime lookup uses: the generated
     * mesh file name and {@code YSMMeshLibrary.tlmMeshIdOf} must stay
     * byte-identical or the runtime script of a model is never found and the
     * bind-armature pose correction silently disables itself.
     */
    private static String sanitize(String value) {
        return YSMMeshLibrary.sanitize(value);
    }

    /**
     * Parse an untrusted "namespace:path" string from a model pack, returning
     * null when it is malformed or uses a traversal form.
     *
     * Model packs are third-party files, so every manifest-declared model /
     * texture / animation reference has to be validated before it is turned into
     * a {@link ResourceLocation} - the resource-location alphabet allows '.' and
     * '/', so "x:../../../etc/passwd" is a *valid* id that a PathPackResources
     * would then resolve outside its own root.
     */
    private static ResourceLocation safeResourceLocation(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl == null || !isSafeResourcePath(rl.getPath())) {
            return null;
        }
        return rl;
    }

    /** A resource path that stays inside its namespace: no empty, "." or ".." segment, no absolute form. */
    private static boolean isSafeResourcePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static Path resolveInPackAssets(Path packRoot, ResourceLocation location) {
        return location == null ? null
                : resolveInPackAssets(packRoot, location.getNamespace(), location.getPath());
    }

    /** Resolve an existing pack file and reject links that leave the pack's assets tree. */
    private static Path resolveInPackAssets(Path packRoot, String namespace, String path) {
        if (namespace == null || path == null || namespace.equals(".") || namespace.equals("..")) {
            return null;
        }
        Path assets = packRoot.resolve("assets");
        Path file = YSMMeshLibrary.resolveInside(assets, namespace + "/" + path);
        if (file == null || !Files.isRegularFile(file)
                || !PackFileIO.isInsideReal(assets, packRoot)
                || !PackFileIO.isInsideReal(file, assets)) {
            return null;
        }
        return file;
    }

    /** Log a rejected model-pack path once per distinct value. */
    private static final Set<String> REJECTED_PATHS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logRejectedPath(String what, String value) {
        if (value != null && REJECTED_PATHS_LOGGED.add(what + "\u0000" + value)) {
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: refusing TLM model-pack {} '{}': not a safe relative path inside the pack",
                    what, value);
        }
    }
}
