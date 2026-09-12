package com.ysmef.geomodel.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

/**
 * GL state capture/restore plus the shared vertex-encoding helpers used by the
 * direct-draw skinning paths.
 *
 * The GPU and CPU paths each override cull / blend / depth state and restore it
 * afterwards; they used to do that with two hand-rolled quartets of
 * {@code glIsEnabled} calls. Keeping one snapshot type means the two paths
 * cannot drift, and keeps the "save → try → finally restore" shape obvious.
 */
public final class GlRenderState {

    private GlRenderState() {}

    /** The GL state the direct-draw paths change. */
    public static final class Snapshot {
        public final boolean cullEnabled;
        public final boolean blendEnabled;
        public final boolean depthTestEnabled;
        public final boolean depthMaskEnabled;

        private Snapshot(boolean cullEnabled, boolean blendEnabled, boolean depthTestEnabled, boolean depthMaskEnabled) {
            this.cullEnabled = cullEnabled;
            this.blendEnabled = blendEnabled;
            this.depthTestEnabled = depthTestEnabled;
            this.depthMaskEnabled = depthMaskEnabled;
        }
    }

    /** Capture the state a direct draw is about to override. Render thread only. */
    public static Snapshot capture() {
        return new Snapshot(
                GL11.glIsEnabled(GL11.GL_CULL_FACE),
                GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
    }

    /** Restore a snapshot taken by {@link #capture()}. Render thread only. */
    public static void restore(Snapshot state) {
        if (state == null) {
            return;
        }
        if (state.cullEnabled) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (state.blendEnabled) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (state.depthTestEnabled) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(state.depthMaskEnabled);
    }

    /** Unbind nothing else: program/VAO/buffer cleanup stays at each call site so
     * every step can be guarded individually (see the finally blocks). */

    /**
     * Pack a normal into the {@code GL_INT_2_10_10_10_REV} layout (the inverse of
     * the unpack in bone_skin.vsh / cpu_skin.vsh).
     *
     * Single implementation on purpose: the GPU mesh builder and the CPU vertex
     * loop both encode normals, and the packing must stay bit-identical to what
     * the two shaders decode.
     */
    public static int packNormal(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 1e-6f && !Float.isNaN(len)) {
            x /= len;
            y /= len;
            z /= len;
        }
        int xi = Math.max(-511, Math.min(511, Math.round(x * 511)));
        int yi = Math.max(-511, Math.min(511, Math.round(y * 511)));
        int zi = Math.max(-511, Math.min(511, Math.round(z * 511)));
        return (xi & 0x3FF) | ((yi & 0x3FF) << 10) | ((zi & 0x3FF) << 20);
    }
}
