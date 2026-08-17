package com.ysmef.geomodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.cpu.YsmCpuRenderPath;
import com.ysmef.geomodel.gpu.YsmGpuRenderPath;
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
import java.util.concurrent.ConcurrentHashMap;

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
    /** Per-part runtime transforms, indexed by part ordinal (see rebindPartTransforms). */
    private OpenMatrix4f[] transformByPart;
    /** partName -> ordinal into transformByPart, built once at rebind time. */
    private final Map<String, Integer> partIndex = new HashMap<>();

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
     *
     * Transforms are stored in a flat array indexed by part ordinal: the supplier
     * (read by Epic Fight's per-part transform upload on the GPU path every frame),
     * the runtime evaluator and the CPU skinning path (YsmCpuRenderPath) all do
     * O(1) array access instead of a string-keyed map lookup per part per frame.
     */
    private void rebindPartTransforms() {
        List<Map.Entry<String, SkinnedMeshPart>> entries = new ArrayList<>(this.parts.entrySet());
        this.transformByPart = new OpenMatrix4f[entries.size()];
        this.partIndex.clear();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, SkinnedMeshPart> entry = entries.get(i);
            String partName = entry.getKey();
            int index = i;
            partIndex.put(partName, index);
            SkinnedMeshPart old = entry.getValue();
            SkinnedMeshPart part = new SkinnedMeshPart(old.getVertices(), null,
                    () -> this.transformByPart[index]);
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
        Integer index = this.partIndex.get(partName);
        if (index != null) {
            this.transformByPart[index] = transform;
        }
    }

    public void clearRuntimeTransforms() {
        if (this.transformByPart != null) {
            java.util.Arrays.fill(this.transformByPart, null);
        }
    }

    /** Number of mesh parts (the CPU path iterates them by ordinal). */
    public int getPartCount() {
        return this.transformByPart != null ? this.transformByPart.length : 0;
    }

    /** The current runtime transform of a part by ordinal, or null for identity. */
    public OpenMatrix4f getPartTransform(int ordinal) {
        if (this.transformByPart == null || ordinal < 0 || ordinal >= this.transformByPart.length) {
            return null;
        }
        return this.transformByPart[ordinal];
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

    /**
     * The texture this mesh draws with (the per-frame override, or the mesh
     * JSON's render_properties texture). Used by the CPU skinning path to bind
     * the model texture directly.
     */
    public ResourceLocation getResolvedTexture() {
        return resolveTexture();
    }

    @Override
    public void draw(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                     Mesh.DrawingFunction drawingFunction, int packedLight, float r, float g, float b, float a,
                     int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        try {
            YSMRuntimeBridge.apply(this, armature, poses);
            ResourceLocation texture = resolveTexture();
            // ModernYSM-style direct GPU skinning path (bone SSBO + skinning shader):
            // one glDrawArrays per model, vertex skinning fully on the GPU. Falls back
            // to Epic Fight's compute-shader path automatically when unavailable.
            if (texture != null && YsmGpuRenderPath.tryRender(this, poseStack, bufferSources, texture,
                    packedLight, r, g, b, a, overlay, armature, poses)) {
                return;
            }
            RenderType finalRenderType = texture != null
                    ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                    : renderType;
            drawWithPreferredPath(poseStack, bufferSources, finalRenderType, drawingFunction,
                    packedLight, r, g, b, a, overlay, armature, poses);
        } finally {
            YSMRuntimeBridge.clearCurrentEntity();
        }
    }

    /**
     * Epic Fight's CPU skinning path (ClientConfig.activateComputeShader = false,
     * the default) renders converted meshes with missing faces, while the compute
     * shader path is correct. Epic Fight creates its ComputeShaderSetup in the
     * SkinnedMesh constructor whenever the GPU supports compute shaders, so the
     * compute path is available regardless of the config.
     *
     * Routing preference: when the GPU skinning path could not take the draw,
     * small meshes and GUI entity previews use this mod's CPU skinning path
     * (CPU skin -> dynamic VBO -> cpu_skin shader); meshes above
     * {@link #CPU_PATH_MAX_VERTICES} use the compute shader in world renders so
     * high-poly many-maid scenes do not pay per-vertex CPU skinning. Without a
     * compute setup (unsupported GPU) the drawPosed fallback is reached, where
     * SkinnedMeshCpuRenderMixin substitutes the CPU renderer. The
     * ysm_geo_compat.force_cpu_render system property skips the compute shader
     * even when available, so the CPU path can be verified on capable hardware.
     */
    private void drawWithPreferredPath(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                                       Mesh.DrawingFunction drawingFunction, int packedLight,
                                       float r, float g, float b, float a, int overlay,
                                       @Nullable Armature armature, OpenMatrix4f[] poses) {
        ComputeShaderSetup setup = poses != null && armature != null ? computeShaderSetup() : null;
        if (setup != null && !YsmCpuRenderPath.isForced()) {
            int positionCount = this.positions().length / 3;
            // The CPU path skins every visible vertex on the render thread every
            // frame. Large GEO models would spend milliseconds per model there,
            // so once a mesh passes this size the Epic Fight compute path is
            // preferred: its vertex skinning runs on the GPU and its render-thread
            // cost stays bounded in many-maid scenes. GUI entity previews keep
            // the CPU preference (Epic Fight disables its compute pass there).
            boolean guiEntityPreview = YsmGpuRenderPath.isGuiEntityProjection()
                    || YsmGpuRenderPath.isYsmPreviewMode();
            if (!guiEntityPreview && positionCount > CPU_PATH_MAX_VERTICES) {
                logComputePreferredOnce(positionCount);
            }
            if ((guiEntityPreview || positionCount <= CPU_PATH_MAX_VERTICES)
                    && !(bufferSources instanceof net.minecraft.client.renderer.OutlineBufferSource)
                    && YsmCpuRenderPath.tryRender(this, poseStack, drawingFunction,
                            packedLight, r, g, b, a, overlay, armature, poses)) {
                return;
            }
            setup.drawWithShader(this, poseStack, bufferSources,
                    EpicFightRenderTypes.getTriangulated(renderType),
                    packedLight, r, g, b, a, overlay, armature, poses);
            return;
        }
        logCpuFallbackOnce();
        // Root cause of the original CPU-path missing faces: EpicFightRenderTypes
        // keeps ONE cache (TRIANGLED_RENDERTYPES_BY_NAME_TEXTURE) shared by
        // getTriangulated / addRenderType / replaceTexture. replaceTexture writes
        // the texture-replaced render type - with the ORIGINAL mode, QUADS for
        // vanilla entity render types - into that cache, and a later
        // getTriangulated call hits the cache and returns the QUADS type as-is.
        // drawPosed then writes triangle-triplet vertices into a QUADS-mode
        // BufferBuilder, so the upload regroups every 4 vertices as a quad and
        // faces scramble/disappear. The compute path is immune because it draws
        // with a hardcoded glDrawArrays(TRIANGLES). makeTriangulated is the
        // cache-independent triangulator (already-TRIANGLES types pass through),
        // so the final Epic Fight drawPosed fallback receives a proper TRIANGLES
        // render type and renders the converted meshes completely.
        this.drawPosed(poseStack, bufferSources.getBuffer(EpicFightRenderTypes.makeTriangulated(renderType)),
                drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
    }

    private static void logCpuFallbackOnce() {
        if (!computeFallbackWarned) {
            computeFallbackWarned = true;
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: no compute shader setup available, using the CPU skinning path "
                            + "(SkinnedMeshCpuRenderMixin substitutes this mod's CPU renderer; "
                            + "Epic Fight's drawPosed remains the final fallback)");
        }
    }

    private static final Field COMPUTE_SETUP_FIELD = findComputeSetupField();

    private static volatile boolean computeFallbackWarned = false;

    /**
     * Meshes at or below this many unique positions may use the CPU skinning
     * path; larger meshes prefer Epic Fight's compute path so the render thread
     * is not skinning hundreds of thousands of vertices per frame in
     * many-maid scenes.
     */
    private static final int CPU_PATH_MAX_VERTICES = 8192;

    private static final Set<String> COMPUTE_PREFERRED_LOGGED = ConcurrentHashMap.newKeySet();

    /** Once per model: the compute path took over because the mesh is too large for CPU skinning. */
    private void logComputePreferredOnce(int positionCount) {
        String key = this.runtimeModelId == null ? "n/a" : this.runtimeModelId;
        if (COMPUTE_PREFERRED_LOGGED.add(key)) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: model '{}' has {} unique vertices (>{}); using Epic Fight's compute path "
                            + "instead of CPU skinning to keep the render thread bounded in many-maid scenes",
                    key, positionCount, CPU_PATH_MAX_VERTICES);
        }
    }

    private static Field findComputeSetupField() {
        try {
            Field field = SkinnedMesh.class.getDeclaredField("computerShaderSetup");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            YSMGeoCompat.LOGGER.error(
                    "YSM-GEO Compat: Epic Fight's compute shader setup field not found; falling back to CPU skinning", e);
            return null;
        }
    }

    @Nullable
    private ComputeShaderSetup computeShaderSetup() {
        if (COMPUTE_SETUP_FIELD == null) {
            return null;
        }
        try {
            return (ComputeShaderSetup) COMPUTE_SETUP_FIELD.get(this);
        } catch (Throwable t) {
            return null;
        }
    }
}
