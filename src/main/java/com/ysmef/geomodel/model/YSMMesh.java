package com.ysmef.geomodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.cpu.YsmCpuRenderPath;
import com.ysmef.geomodel.gpu.YsmGpuRenderPath;
import com.ysmef.geomodel.model.runtime.YsmBindArmature;
import com.ysmef.geomodel.model.runtime.YSMRuntimeBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
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
        // 姿态矫正（移植自主项目的 YsmBindArmature 架构）：Epic Fight 的战斗动画围绕
        // 绑定姿势（Steve 体型）的关节旋转，而转换后的模型按自身关节轴心刚性蒙皮，
        // 挥砍时四肢会绕 Steve 的关节位置旋转导致与身体分离（头身分离、四肢错位）。
        // 这里将动画姿势重新求值到该模型自己的绑定骨架（关节平移来自模型骨骼
        // pivot，旋转帧与拓扑不变），使旋转轴心落在模型的真实关节上；绑定姿势
        // 不变式（pose x toOrigin = I）保证静止形态不受影响。仅当 poses 是当前
        // armature 的实时姿势矩阵时才生效（EntitySnapshot 等快照路径传独立数组，
        // 保持原样）。姿势由 YsmArmaturePoseMixin 在 Armature#setPose 时捕获。
        boolean rebindApplied = false;
        LivingEntity renderEntity = YSMRuntimeBridge.getCurrentEntity();
        if (this.runtimeModelId != null && armature != null && poses != null
                && poses == armature.getPoseMatrices()) {
            try {
                yesman.epicfight.api.animation.Pose captured = YsmBindArmature.findPose(armature);
                if (captured != null) {
                    yesman.epicfight.model.armature.HumanoidArmature bind = YsmBindArmature.getArmature(this.runtimeModelId, this);
                    if (bind != null) {
                        yesman.epicfight.api.animation.Pose poseToApply = captured;
                        if (renderEntity != null) {
                            poseToApply = YsmBindArmature.correctWheelPose(
                                    renderEntity.getUUID(), captured, armature, bind);
                        }
                        bind.setPose(poseToApply);
                        armature = bind;
                        poses = bind.getPoseMatrices();
                        rebindApplied = true;
                    }
                }
            } catch (Throwable t) {
                // A bind-armature failure must never break the entity render:
                // fall back to the un-corrected pose path for this draw.
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: pose correction failed for model '{}', drawing without it", this.runtimeModelId, t);
            }
        }
        try {
            YSMRuntimeBridge.apply(this, armature, poses);
            ResourceLocation texture = resolveTexture();
            // ModernYSM-style direct GPU skinning path (bone SSBO + skinning shader):
            // one glDrawArrays per model, vertex skinning fully on the GPU. Falls back
            // to Epic Fight's compute-shader path automatically when unavailable.
            if (texture != null && YsmGpuRenderPath.tryRender(this, poseStack, bufferSources, texture,
                    packedLight, r, g, b, a, overlay, armature, poses)) {
                logRebindOnce(rebindApplied);
                return;
            }
            RenderType finalRenderType = texture != null
                    ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                    : renderType;
            drawWithPreferredPath(poseStack, bufferSources, finalRenderType, drawingFunction,
                    packedLight, r, g, b, a, overlay, armature, poses);
            logRebindOnce(rebindApplied);
        } finally {
            YSMRuntimeBridge.clearCurrentEntity();
        }
    }

    /** Once per model: the pose-correction rebind was actually applied on the live pose path. */
    private static final Set<String> DIAG_REBIND_LOGGED = ConcurrentHashMap.newKeySet();

    private void logRebindOnce(boolean rebindApplied) {
        String modelId = this.runtimeModelId;
        if (rebindApplied && modelId != null && DIAG_REBIND_LOGGED.add(modelId)) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: [diag] bind-armature pose correction active for model '{}' "
                            + "(combat rotations pivot at the model's own joints)", modelId);
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
            // Epic Fight's compute paths (VanillaComputeShaderSetup and the Iris
            // variant used under shader packs) stage poses.length + partCount
            // matrices in the static ComputeShaderSetup.TOTAL_POSES array, whose
            // capacity is EpicFightSharedConstants.MAX_JOINTS (1000). A converted
            // model with more joints+parts than that overflows the array
            // (TOTAL_POSES[poses.length + partIdx] and the POSE_BO upload) and
            // crashes the game with an ArrayIndexOutOfBoundsException - draw it
            // with this mod's own CPU skinning path instead, even under a shader
            // pack (the pack's shaders are bypassed for this model, but the
            // alternative is a crash).
            if (poses != null && poses.length + this.getAllParts().size()
                    > yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS) {
                renderOverCapacity(poseStack, bufferSources, drawingFunction, packedLight,
                        r, g, b, a, overlay, armature, poses, "compute");
                return;
            }
            setup.drawWithShader(this, poseStack, bufferSources,
                    EpicFightRenderTypes.getTriangulated(renderType),
                    packedLight, r, g, b, a, overlay, armature, poses);
            return;
        }
        logCpuFallbackOnce();
        // Epic Fight's drawPosed stages poses.length matrices in the same static
        // TOTAL_POSES array (MAX_JOINTS = 1000), so the same overflow guard
        // applies to the CPU fallback.
        if (poses != null && poses.length > yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS) {
            renderOverCapacity(poseStack, bufferSources, drawingFunction, packedLight,
                    r, g, b, a, overlay, armature, poses, "drawPosed");
            return;
        }
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

    /**
     * Renders (or skips) a mesh whose joint/part count exceeds Epic Fight's
     * static pose-array capacity (EpicFightSharedConstants.MAX_JOINTS), which
     * every Epic Fight render path would overflow. The last-resort CPU skinning
     * path has no fixed capacity; outline passes are skipped entirely (a missing
     * outline beats a crash, and the main pass still renders the model).
     *
     * Ported from the main project's YSMMesh#renderOverCapacity.
     */
    private void renderOverCapacity(PoseStack poseStack, MultiBufferSource bufferSources,
                                    Mesh.DrawingFunction drawingFunction, int packedLight,
                                    float r, float g, float b, float a, int overlay,
                                    @Nullable Armature armature, OpenMatrix4f[] poses, String blockedPath) {
        logOverCapacityOnce(poses, blockedPath);
        if (bufferSources instanceof net.minecraft.client.renderer.OutlineBufferSource) {
            return;
        }
        YsmCpuRenderPath.tryRenderLastResort(this, poseStack, drawingFunction,
                packedLight, r, g, b, a, overlay, armature, poses);
    }

    private static final Set<String> OVER_CAPACITY_LOGGED = ConcurrentHashMap.newKeySet();

    /** Once per model + path: Epic Fight's pose array is too small for this mesh. */
    private void logOverCapacityOnce(OpenMatrix4f[] poses, String blockedPath) {
        String key = (this.runtimeModelId == null ? "n/a" : this.runtimeModelId) + "|" + blockedPath;
        if (OVER_CAPACITY_LOGGED.add(key)) {
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: model '{}' has {} joints + {} parts, exceeding Epic Fight's pose array "
                            + "capacity (MAX_JOINTS={}); the {} path would crash with an ArrayIndexOutOfBoundsException. "
                            + "Rendering with this mod's CPU skinning path instead (shader packs are bypassed for this model).",
                    key, poses.length, this.getAllParts().size(),
                    yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS, blockedPath);
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
