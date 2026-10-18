package com.ysmef.geomodel.model;

import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.List;

/**
 * Stable entry point for generated mesh resources. Each lifecycle state belongs
 * to its corresponding pack, texture, decoder, or residency component.
 */
public final class YSMMeshLibrary {
    static final int RELEASE_DELAY_TICKS = 5;
    private static final YSMMeshResidency.MeshRegistry TLM_REGISTRY = new YSMMeshResidency.MeshRegistry() {
        @Override public String meshOwnerOf(String modelId) { return TlmModelLibrary.meshOwnerOf(modelId); }
        @Override public void bindTexturesOfMesh(String meshOwnerId) { TlmModelLibrary.bindTexturesOfMesh(meshOwnerId); }
        @Override public yesman.epicfight.api.client.model.Meshes.MeshAccessor<YSMMesh> accessorOf(String modelId) {
            return TlmModelLibrary.accessorOf(modelId);
        }
        @Override public void invalidateRuntimeModelsOfMesh(String meshOwnerId) {
            TlmModelLibrary.invalidateRuntimeModelsOfMesh(meshOwnerId);
        }
    };

    private YSMMeshLibrary() {}

    public static Path getPackRoot() { return GeneratedPackStore.getPackRoot(); }
    public static Path getMeshDir() { return GeneratedPackStore.getMeshDir(); }
    public static Path getRuntimeFile(String meshId) { return GeneratedPackStore.getRuntimeFile(meshId); }
    public static String meshIdOf(String modelId) { return GeneratedPackStore.meshIdOf(modelId); }
    public static String tlmMeshIdOf(String modelId) { return GeneratedPackStore.tlmMeshIdOf(modelId); }
    public static String sanitize(String value) { return GeneratedPackStore.sanitize(value); }
    public static Path resolveInside(Path root, String relative) { return GeneratedPackStore.resolveInside(root, relative); }
    public static void preparePackFolder() { GeneratedPackStore.preparePackFolder(); }
    public static void deleteStalePackTextures() {
        GeneratedPackStore.deleteStalePackTextures(YSMTextureRegistry.registeredPackTexturePaths());
    }

    public static ResourceLocation registerTextureBytes(String relativePath, byte[] data) {
        return YSMTextureRegistry.registerTextureBytes(relativePath, data);
    }
    public static void ensureTextureUploaded(ResourceLocation rl) { YSMTextureRegistry.ensureTextureUploaded(rl); }
    public static void processPendingTextureUploads() { YSMTextureRegistry.processPendingTextureUploads(); }
    public static boolean isTextureUploaded(ResourceLocation rl) { return YSMTextureRegistry.isTextureUploaded(rl); }
    public static boolean isTranslucentTexture(ResourceLocation rl) { return YSMTextureRegistry.isTranslucentTexture(rl); }
    public static void bindModelTexture(String modelId, ResourceLocation texture) {
        YSMTextureRegistry.bindModelTexture(modelId, texture);
    }
    public static List<ResourceLocation> releaseTexturesOfModel(String modelId) {
        return YSMTextureRegistry.releaseTexturesOfModel(modelId);
    }
    public static void processPendingTextureReleases() { YSMTextureRegistry.processPendingTextureReleases(); }

    public static void registerMeshReleaser(MeshReleaser releaser) { YSMMeshResidency.registerMeshReleaser(releaser); }
    public static void disposeMesh(YSMMesh mesh) { YSMMeshResidency.disposeMesh(mesh); }
    public static void disposeAllPaths() { YSMMeshResidency.disposeAllPaths(); }
    public static void touchModel(String modelId) { YSMMeshResidency.touchModel(modelId); }
    public static int loadedModelCount() { return YSMMeshResidency.loadedModelCount(); }
    public static int modelCacheCap() { return YSMMeshResidency.modelCacheCap(); }
    public static void markMeshLoaded(String modelId) { YSMMeshResidency.markMeshLoaded(modelId, TLM_REGISTRY); }
    public static void trimIfNeeded() { YSMMeshResidency.trimIfNeeded(TLM_REGISTRY); }
    public static void scheduleMeshRelease(YSMMesh mesh) { YSMMeshResidency.scheduleMeshRelease(mesh); }
    public static void processPendingMeshReleases() { YSMMeshResidency.processPendingMeshReleases(); }
    public static void preloadRuntimeAsync(String modelId) { YSMRuntimePreloader.preloadAsync(modelId); }

    /** Preserve the reload order: old meshes, texture generation, residency. */
    public static void invalidateAll() {
        YSMMeshResidency.releasePendingMeshesOnReload();
        YSMTextureRegistry.invalidateAll();
        YSMMeshResidency.clearTrackingOnReload();
    }

    /** Render-thread drain of deferred resource work. */
    public static void tick() {
        YSMTextureRegistry.processPendingTextureUploads();
        YSMTextureRegistry.processPendingTextureReleases();
        YSMMeshResidency.processPendingMeshReleases();
        YSMMeshResidency.trimIfNeeded(TLM_REGISTRY);
    }
}
