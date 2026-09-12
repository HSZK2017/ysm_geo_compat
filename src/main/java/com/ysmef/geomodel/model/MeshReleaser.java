package com.ysmef.geomodel.model;

/**
 * Resource-release seam registered by each direct draw path.
 *
 * Kept separate from {@link RenderBridgeRegistry} so the model package can ask
 * for "release this mesh" without pulling in the draw-path interfaces, and so a
 * future eviction/LRU can release GPU buffers through one call.
 */
public interface MeshReleaser {

    /** Free the draw-path resources of one mesh (called on eviction / re-conversion). */
    void disposeMesh(YSMMesh mesh);

    /** Free every draw-path resource (called on a resource reload). */
    void disposeAll();
}
