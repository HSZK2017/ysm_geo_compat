package com.ysmef.geomodel.model;

import com.ysmef.geomodel.YSMGeoCompat;
import yesman.epicfight.api.client.model.Meshes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks resident meshes, evicts least-used instances, and releases their draw resources. */
final class YSMMeshResidency {

    private YSMMeshResidency() {}

    /** Model registry boundary supplied by the public integration facade. */
    interface MeshRegistry {
        String meshOwnerOf(String modelId);
        void bindTexturesOfMesh(String meshOwnerId);
        Meshes.MeshAccessor<YSMMesh> accessorOf(String modelId);
        void invalidateRuntimeModelsOfMesh(String meshOwnerId);
    }

    /**
     * Ticks an evicted mesh/texture is kept alive after eviction.
     *
     * The mesh instance is shared by every entity using the model and may still
     * be drawn later in the current frame (shadow/outline/capture passes select
     * meshes before the main pass draws them), and a texture may still be bound
     * by a draw command already recorded. Destroying either immediately would
     * use freed GL objects; by the time the delay elapses the mesh has been
     * dropped from Epic Fight's cache and a fresh instance has taken over.
     */

    /** Evicted meshes are no longer in Epic Fight's cache and must be destroyed on reload. */
    private static final DelayedReleaseQueue<YSMMesh> PENDING_MESH_RELEASES =
            new DelayedReleaseQueue<>(YSMMeshLibrary.RELEASE_DELAY_TICKS, YSMMeshResidency::releaseMesh);

    /**
     * LRU usage tracking in access order: every mesh lookup touches its model.
     * When more models are loaded than the configured cap, the least recently
     * used ones release their GL resources (see {@link #trimIfNeeded(MeshRegistry)}).
     */
    static final java.util.LinkedHashMap<String, Boolean> ACCESS_ORDER =
            new java.util.LinkedHashMap<>(64, 0.75f, true);

    /** Models whose mesh was actually instantiated, i.e. own GL resources. */
    static final Set<String> LOADED_MODELS = ConcurrentHashMap.newKeySet();

    /**
     * Set when Epic Fight's private mesh cache cannot be reached: eviction is
     * then disabled rather than performed partially. Dropping the entry from our
     * own tables while Epic Fight still holds the mesh would leave a dead
     * instance in a cache that keeps handing it out, and every later draw would
     * use freed GL objects - far worse than the memory eviction saves.
     */
    private static volatile boolean EVICTION_DISABLED = false;

    /** Whether the first eviction batch was already reported at info level. */
    private static volatile boolean EVICTION_LOGGED = false;

    /**
     * Draw-path resource releasers, registered by the gpu/cpu packages on first
     * class load. Keeps the model package from importing them (see
     * {@link com.ysmef.geomodel.model.MeshReleaser}).
     */
    private static final List<MeshReleaser> RELEASERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void registerMeshReleaser(MeshReleaser releaser) {
        if (releaser != null) {
            RELEASERS.add(releaser);
        }
    }

    /** Free the draw-path resources of one mesh on every registered path. */
    static void disposeMesh(YSMMesh mesh) {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeMesh(mesh);
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: mesh release failed", t);
            }
        }
    }

    /** Free every direct-draw resource (resource reload). */
    static void disposeAllPaths() {
        for (MeshReleaser releaser : RELEASERS) {
            try {
                releaser.disposeAll();
            } catch (Throwable t) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: render-path release failed", t);
            }
        }
    }

    /** Record a model usage (LRU touch): the next trim evicts it last. */
    static void touchModel(String modelId) {
        if (modelId == null || EVICTION_DISABLED || !LOADED_MODELS.contains(modelId)) {
            return;
        }
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.put(modelId, Boolean.TRUE);
        }
    }

    /** How many models the LRU currently tracks, i.e. hold GL resources. */
    static int loadedModelCount() {
        synchronized (ACCESS_ORDER) {
            return ACCESS_ORDER.size();
        }
    }

    /** The configured resident-model cap. */
    static int modelCacheCap() {
        return com.ysmef.geomodel.config.YSMCompatConfig.LAZY_MODEL_CACHE_SIZE.get();
    }

    /**
     * Record that a model's mesh was actually instantiated: from here on it owns
     * GL resources and becomes a candidate for eviction.
     */
    static void markMeshLoaded(String modelId, MeshRegistry registry) {
        if (modelId == null) {
            return;
        }
        String meshOwnerId = registry.meshOwnerOf(modelId);
        if (LOADED_MODELS.add(meshOwnerId)) {
            registry.bindTexturesOfMesh(meshOwnerId);
            // Eviction also drops the compiled runtime model. Claim its preload
            // before this mesh is drawn so get() cannot compile it in the pass.
            YSMRuntimePreloader.preloadAsync(meshOwnerId);
        }
        touchModel(meshOwnerId);
    }

    /**
     * Release the GL resources of the least recently used models while more than
     * the configured cap are loaded. Must run on the render thread.
     *
     * Eviction is self-healing because Epic Fight's {@code MeshAccessor.get()}
     * checks its mesh cache on every call and rebuilds the mesh from the
     * generated JSON when the entry is gone - so the next draw of an evicted maid
     * transparently re-loads her mesh. The generated JSON stays on disk, so
     * nothing is re-converted and no model is permanently degraded.
     */
    static void trimIfNeeded(MeshRegistry registry) {
        if (EVICTION_DISABLED) {
            return;
        }
        int cap = com.ysmef.geomodel.config.YSMCompatConfig.LAZY_MODEL_CACHE_SIZE.get();
        List<String> victims;
        synchronized (ACCESS_ORDER) {
            if (ACCESS_ORDER.size() <= cap) {
                return;
            }
            // Oldest entries first; the just-touched models are at the tail.
            victims = new java.util.ArrayList<>(ACCESS_ORDER.keySet());
        }
        int evicted = 0;
        for (String victim : victims) {
            if (EVICTION_DISABLED) {
                // The mesh cache turned out to be unreachable: stop immediately so
                // the remaining victims keep their meshes AND their textures.
                break;
            }
            int loaded;
            synchronized (ACCESS_ORDER) {
                loaded = ACCESS_ORDER.size();
            }
            if (loaded <= cap) {
                break;
            }
            synchronized (ACCESS_ORDER) {
                ACCESS_ORDER.remove(victim);
            }
            if (evictModel(victim, registry)) {
                evicted++;
            }
        }
        if (evicted > 0) {
            // Once per session at info (the log line is the only evidence the LRU
            // runs at all); the repeats stay on debug so a small cap does not
            // flood the log while the player moves between models.
            if (EVICTION_LOGGED) {
                YSMGeoCompat.LOGGER.debug("YSM-GEO Compat: evicted {} least recently used models (cap {})",
                        evicted, cap);
            } else {
                EVICTION_LOGGED = true;
                YSMGeoCompat.LOGGER.info(
                        "YSM-GEO Compat: evicted {} least recently used models (cap {}); "
                                + "they re-load from the generated mesh JSON on their next draw",
                        evicted, cap);
            }
        }
    }

    /**
     * Release one model's GL resources: its mesh instance, its textures and its
     * compiled runtime scripts. Returns true when the model's mesh was released.
     *
     * The mesh instance is taken out of Epic Fight's own mesh cache first. That
     * cache has no removal API, so reaching it takes reflection; when it cannot
     * be reached the eviction is abandoned entirely (see EVICTION_DISABLED)
     * rather than leaving a destroyed mesh behind in a cache that keeps handing
     * it out. Nothing is released on any failure path: a model that keeps its
     * mesh must keep its textures too.
     */
    private static boolean evictModel(String modelId, MeshRegistry registry) {
        if (!LOADED_MODELS.remove(modelId)) {
            // Never instantiated (or already evicted): the model owns no GL
            // resources, so there is nothing to release and its textures stay
            // warm for the next lookup.
            return false;
        }
        Meshes.MeshAccessor<YSMMesh> accessor = registry.accessorOf(modelId);
        if (accessor == null) {
            // Not registered any more (the registry was replaced): its mesh went
            // with it, and the textures are re-read from the pack on next use.
            YSMTextureRegistry.releaseTexturesOfModel(modelId);
            registry.invalidateRuntimeModelsOfMesh(modelId);
            return false;
        }
        YSMMesh mesh = takeFromEfMeshCache(accessor);
        if (mesh == null) {
            // Either Epic Fight already dropped the instance (its own reload) or
            // the cache is unreachable; in both cases the model keeps its textures
            // rather than losing them for nothing.
            if (EVICTION_DISABLED) {
                // The accessor may still own its mesh; keep the residency count
                // so prewarming stops at the cap even without eviction support.
                LOADED_MODELS.add(modelId);
                synchronized (ACCESS_ORDER) {
                    ACCESS_ORDER.put(modelId, Boolean.TRUE);
                }
            }
            return false;
        }
        scheduleMeshRelease(mesh);
        YSMTextureRegistry.releaseTexturesOfModel(modelId);
        registry.invalidateRuntimeModelsOfMesh(modelId);
        return true;
    }

    /**
     * Remove the accessor's loaded mesh from Epic Fight's private static mesh
     * cache ({@code Meshes.MESHES}, keyed by accessor, no public removal API) and
     * return it.
     *
     * Returns null when the cache holds no entry for the accessor. Disables
     * eviction for the rest of the session and returns null when the cache cannot
     * be reached at all: the caller must then not release anything, because Epic
     * Fight would keep drawing the destroyed instance.
     */
    @SuppressWarnings("unchecked")
    private static YSMMesh takeFromEfMeshCache(Meshes.MeshAccessor<?> accessor) {
        if (EVICTION_DISABLED) {
            return null;
        }
        try {
            java.lang.reflect.Field field = Meshes.class.getDeclaredField("MESHES");
            field.setAccessible(true);
            Object map = field.get(null);
            if (!(map instanceof Map<?, ?>)) {
                throw new IllegalStateException("not a map");
            }
            Object removed = ((Map<Object, Object>) map).remove(accessor);
            return removed instanceof YSMMesh mesh ? mesh : null;
        } catch (Throwable t) {
            if (!EVICTION_DISABLED) {
                EVICTION_DISABLED = true;
                YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: Epic Fight's mesh cache is unreachable ({}); "
                                + "model eviction is disabled for this session", t.toString());
            }
            return null;
        }
    }

    /**
     * Queue one evicted mesh for the delayed release. Must be called on the
     * render thread; the GL deletions happen {@link YSMMeshLibrary#RELEASE_DELAY_TICKS} ticks
     * later (see {@link #processPendingMeshReleases}).
     */
    static void scheduleMeshRelease(YSMMesh mesh) {
        if (mesh != null) {
            PENDING_MESH_RELEASES.schedule(mesh);
        }
    }

    /**
     * Destroy evicted meshes whose delay elapsed. Called from the client tick:
     * the GL deletions require the render thread.
     */
    static void processPendingMeshReleases() {
        PENDING_MESH_RELEASES.processTick();
    }

    private static void releaseMesh(YSMMesh mesh) {
        try {
            mesh.destroy();
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to destroy evicted mesh", t);
        }
        try {
            disposeMesh(mesh);
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: failed to release evicted mesh draw paths", t);
        }
    }

    /** Release meshes no longer owned by Epic Fight before a resource reload. */
    static void releasePendingMeshesOnReload() {
        PENDING_MESH_RELEASES.releaseAll();
    }

    /** Forget residency after the old texture generation has been revoked. */
    static void clearTrackingOnReload() {
        LOADED_MODELS.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
    }

}
