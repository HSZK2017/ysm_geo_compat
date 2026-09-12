package com.ysmef.geomodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;

/**
 * Inversion-of-control seam between the converted-mesh data model and the direct
 * draw paths in the {@code gpu} / {@code cpu} packages.
 *
 * The mesh model used to import those classes directly, which both created a
 * {@code model <-> gpu/cpu} package cycle and made the draw dispatch impossible
 * to substitute. The paths now register themselves here on first class load and
 * {@link YSMMesh} only talks to this registry, so the dependency runs one way
 * (render paths -> model data).
 */
public final class RenderBridgeRegistry {

    /** The direct GPU skinning path (bone SSBO + skinning shader). */
    public interface GpuSkinRender {
        boolean tryRender(YSMMesh mesh, PoseStack poseStack,
                          net.minecraft.client.renderer.MultiBufferSource bufferSources,
                          ResourceLocation texture, int packedLight,
                          float r, float g, float b, float a, int overlay,
                          @Nullable Armature armature, @Nullable OpenMatrix4f[] poses);

        boolean isGuiEntityProjection();

        boolean isYsmPreviewMode();
    }

    /** The CPU per-vertex skinning path. */
    public interface CpuSkinRender {
        /** True when the forced-CPU system property is set (skips the compute path). */
        boolean isForced();

        boolean tryRender(YSMMesh mesh, PoseStack poseStack, Mesh.DrawingFunction drawingFunction,
                          int packedLight, float r, float g, float b, float a, int overlay,
                          @Nullable Armature armature, @Nullable OpenMatrix4f[] poses);

        /** Last-resort draw for meshes that would overflow Epic Fight's pose array. */
        boolean tryRenderLastResort(YSMMesh mesh, PoseStack poseStack, Mesh.DrawingFunction drawingFunction,
                                    int packedLight, float r, float g, float b, float a, int overlay,
                                    @Nullable Armature armature, @Nullable OpenMatrix4f[] poses);
    }

    /**
     * Resource release of a converted mesh, registered by the draw paths so the
     * model package never has to import them.
     */
    public interface MeshReleaser {
        void disposeMesh(YSMMesh mesh);

        void disposeAll();
    }

    private static volatile GpuSkinRender gpu;
    private static volatile CpuSkinRender cpu;

    private RenderBridgeRegistry() {}

    public static void registerGpu(GpuSkinRender render) {
        gpu = render;
    }

    public static void registerCpu(CpuSkinRender render) {
        cpu = render;
    }

    /** The registered GPU path, or null while it has not been class-loaded yet. */
    @Nullable
    public static GpuSkinRender gpu() {
        return gpu;
    }

    /** The registered CPU path, or null while it has not been class-loaded yet. */
    @Nullable
    public static CpuSkinRender cpu() {
        return cpu;
    }
}
