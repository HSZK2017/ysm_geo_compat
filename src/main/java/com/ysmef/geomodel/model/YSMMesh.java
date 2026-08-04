package com.ysmef.geomodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.runtime.YSMRuntimeBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.*;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;

/**
 * A HumanoidMesh loaded from a generated Epic Fight animmodels JSON (see
 * EFMeshJsonWriter / YSMMeshLibrary).
 *
 * Epic Fight's patched render pipeline always draws the mesh with the render type of the
 * vanilla entity renderer (i.e. the player's own skin texture). To display the YSM model's
 * texture instead, the render type's texture is replaced here, keeping every other render
 * state (translucency, outline, cull, ...) from the original render type.
 *
 * The default texture comes from the mesh JSON's render_properties; the patched renderer
 * can override it per frame (players may select different textures of the same model).
 *
 * YSM models change shape at runtime through molang-driven bone animations. Each YSM
 * bone is a separate Epic Fight part ("y/<boneName>") whose vanilla part transform is
 * fed from the runtime script evaluator (see YSMRuntimeBridge): every frame the scripts
 * decide which bones are hidden and which bind-space delta each visible bone gets, so
 * the mesh replicates YSM's model-changing behavior (variant forms, secondary bones).
 */
public class YSMMesh extends HumanoidMesh {

    private ResourceLocation textureOverride;
    private String runtimeModelId;
    private final Map<String, OpenMatrix4f> runtimeTransforms = new HashMap<>();

    public YSMMesh(Map<String, Number[]> arrayMap,
                   Map<MeshPartDefinition, List<VertexBuilder>> parts,
                   @Nullable SkinnedMesh parent,
                   RenderProperties properties) {
        super(arrayMap, parts, parent, properties);
        rebindPartTransforms();
    }

    /**
     * Re-creates every part with a vanilla-part-transform supplier fed from the
     * runtime script evaluator, so per-bone transforms can be injected per frame.
     * The compute-shader part binding (partVBO, assigned when Epic Fight built the
     * ComputeShaderSetup during the super constructor) is carried over verbatim.
     */
    private void rebindPartTransforms() {
        List<Map.Entry<String, SkinnedMeshPart>> entries = new ArrayList<>(this.parts.entrySet());
        for (Map.Entry<String, SkinnedMeshPart> entry : entries) {
            String partName = entry.getKey();
            SkinnedMeshPart old = entry.getValue();
            SkinnedMeshPart part = new SkinnedMeshPart(old.getVertices(), null, () -> this.runtimeTransforms.get(partName));
            part.initVBO(old.getPartVBO());
            entry.setValue(part);
        }
    }

    public void setRuntimeModelId(String modelId) {
        this.runtimeModelId = modelId;
    }

    public String getRuntimeModelId() {
        return this.runtimeModelId;
    }

    public void setRuntimeTransform(String partName, OpenMatrix4f transform) {
        this.runtimeTransforms.put(partName, transform);
    }

    public void clearRuntimeTransforms() {
        this.runtimeTransforms.clear();
    }

    /** Typed view of this mesh's part entries for the runtime evaluator. */
    public Set<Map.Entry<String, MeshPart>> getPartEntrySetSafe() {
        return (Set<Map.Entry<String, MeshPart>>) (Set<?>) this.getPartEntry();
    }

    public void setTextureOverride(ResourceLocation texture) {
        this.textureOverride = texture;
    }

    private ResourceLocation resolveTexture() {
        if (this.textureOverride != null) {
            return this.textureOverride;
        }
        if (this.getRenderProperties() != null && this.getRenderProperties().customTexturePath() != null) {
            return this.getRenderProperties().customTexturePath();
        }
        return null;
    }

    @Override
    public void draw(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                     Mesh.DrawingFunction drawingFunction, int packedLight, float r, float g, float b, float a,
                     int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        try {
            YSMRuntimeBridge.apply(this, armature, poses);
            ResourceLocation texture = resolveTexture();
            RenderType finalRenderType = texture != null
                    ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                    : renderType;
            if (!tryDrawWithComputeShader(poseStack, bufferSources, finalRenderType, packedLight,
                    r, g, b, a, overlay, armature, poses)) {
                super.draw(poseStack, bufferSources, finalRenderType, drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
            }
        } finally {
            YSMRuntimeBridge.clearCurrentEntity();
        }
    }

    /**
     * Epic Fight's CPU skinning path (ClientConfig.activateComputeShader = false,
     * the default) renders converted meshes with missing faces, while the compute
     * shader path is correct. Epic Fight creates its ComputeShaderSetup in the
     * SkinnedMesh constructor whenever the GPU supports compute shaders, so this
     * forces the compute path for our meshes regardless of the config; it falls
     * back to the CPU path with a one-time warning when no setup is available.
     */
    private static final Field COMPUTE_SETUP_FIELD = findComputeSetupField();

    private static volatile boolean computeFallbackWarned = false;

    private static Field findComputeSetupField() {
        try {
            Field field = SkinnedMesh.class.getDeclaredField("computerShaderSetup");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            YSMGeoCompat.LOGGER.error(
                    "YSM-GEO Compat: Epic Fight's compute shader setup field not found; falling back to CPU skinning (converted meshes may show missing faces)", e);
            return null;
        }
    }

    private boolean tryDrawWithComputeShader(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                                             int packedLight, float r, float g, float b, float a,
                                             int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        if (poses == null || armature == null || COMPUTE_SETUP_FIELD == null) {
            warnComputeShaderUnavailableOnce();
            return false;
        }
        try {
            ComputeShaderSetup setup = (ComputeShaderSetup) COMPUTE_SETUP_FIELD.get(this);
            if (setup == null) {
                warnComputeShaderUnavailableOnce();
                return false;
            }
            setup.drawWithShader(this, poseStack, bufferSources,
                    EpicFightRenderTypes.getTriangulated(renderType),
                    packedLight, r, g, b, a, overlay, armature, poses);
            return true;
        } catch (IllegalAccessException e) {
            YSMGeoCompat.LOGGER.error("YSM-GEO Compat: failed to access Epic Fight compute shader setup, falling back to CPU skinning", e);
            return false;
        }
    }

    private static void warnComputeShaderUnavailableOnce() {
        if (!computeFallbackWarned) {
            computeFallbackWarned = true;
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: Epic Fight compute shader skinning unavailable, falling back to the CPU skinning path (converted meshes may render with missing faces)");
        }
    }
}
