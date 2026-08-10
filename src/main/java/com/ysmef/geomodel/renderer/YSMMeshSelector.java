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

import java.util.Set;
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

    private static final Set<String> LOGGED_MESH_USE = ConcurrentHashMap.newKeySet();

    private YSMMeshSelector() {}

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
        String key = entity.getUUID() + "|" + modelId + "|" + textureName;
        if (LOGGED_MESH_USE.add(key)) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: rendering '{}' with converted TLM base mesh (model='{}', texture='{}' -> {})",
                    displayName, modelId, textureName, texture);
        }
    }
}
