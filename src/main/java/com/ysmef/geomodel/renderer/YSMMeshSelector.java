package com.ysmef.geomodel.renderer;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.YSMMesh;
import com.ysmef.geomodel.model.YSMMeshLibrary;
import com.ysmef.geomodel.model.runtime.YSMRuntimeBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared mesh-selection logic used by the EpicFight_TouhouLittleMaid maid
 * renderer hook.
 *
 * Returns a mesh accessor for the maid's current TLM model-pack GEO model
 * (with the texture override applied), or null to let Epic Fight use its
 * default mesh.
 */
public final class YSMMeshSelector {

    /**
     * Last logged (modelId, textureName) pair per entity. This runs every frame
     * per drawn maid, so the pair is compared against the cached one instead of
     * building a "uuid|modelId|texture" string every frame - and the set stays
     * bounded by the number of entities instead of growing per distinct
     * combination seen.
     */
    private static final Map<UUID, String[]> LOGGED_MESH_USE = new ConcurrentHashMap<>();

    private YSMMeshSelector() {}

    /** Forget per-player once-log state (resource reload / world leave). */
    public static void clear() {
        LOGGED_MESH_USE.clear();
    }

    /**
     * Shared core: apply the runtime model id, current entity and texture
     * override to a resolved mesh accessor and return it for the Epic Fight
     * render pipeline.
     */
    public static AssetAccessor<HumanoidMesh> selectResolvedMesh(LivingEntity entity,
                                                                 Meshes.MeshAccessor<YSMMesh> accessor,
                                                                 String modelId, ResourceLocation texture,
                                                                 String textureName, String displayName) {
        if (entity == null || accessor == null) {
            return null;
        }
        try {
            YSMMesh mesh = accessor.get();
            // The mesh now owns GL resources (whether it was built here or
            // re-loaded after an eviction), so record it for the LRU cache.
            YSMMeshLibrary.markMeshLoaded(modelId);
            mesh.setRuntimeModelId(modelId);
            YSMRuntimeBridge.setCurrentEntity(entity);
            if (texture != null) {
                YSMMeshLibrary.ensureTextureUploaded(texture);
                mesh.setTextureOverride(texture);
            }
            logMeshUsedOnce(entity, modelId, textureName, texture, displayName);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: failed to load generated mesh for '{}', falling back to Epic Fight default mesh",
                    modelId, t);
            return null;
        }

        @SuppressWarnings("unchecked")
        AssetAccessor<HumanoidMesh> result = (AssetAccessor<HumanoidMesh>) (AssetAccessor<?>) accessor;
        return result;
    }

    private static void logMeshUsedOnce(LivingEntity entity, String modelId, String textureName,
                                        ResourceLocation texture, String displayName) {
        // Runs every frame per drawn maid: compare against the cached pair rather
        // than building a "uuid|modelId|texture" string every frame.
        String[] prev = LOGGED_MESH_USE.get(entity.getUUID());
        if (prev != null && prev[0].equals(modelId) && java.util.Objects.equals(prev[1], textureName)) {
            return;
        }
        LOGGED_MESH_USE.put(entity.getUUID(), new String[]{modelId, textureName});
        YSMGeoCompat.LOGGER.info(
                "YSM-GEO Compat: rendering '{}' with converted TLM base mesh (model='{}', texture='{}' -> {})",
                displayName, modelId, textureName, texture);
    }
}
