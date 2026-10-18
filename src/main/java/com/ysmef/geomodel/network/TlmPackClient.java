package com.ysmef.geomodel.network;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.config.YSMCompatConfig;
import com.ysmef.geomodel.event.YSMCompatClientEvents;
import com.ysmef.geomodel.model.TlmPackArchive;
import com.ysmef.geomodel.model.TlmPackCatalog;
import com.ysmef.geomodel.model.TlmModelLibrary;
import com.ysmef.geomodel.model.TlmServerOverlay;
import com.ysmef.geomodel.tlm.TlmClientPackBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side model query, verified download, session overlay and reload coordination. */
public final class TlmPackClient {
    public record Resolution(boolean pending, boolean sourceKnown, String unavailableReason) {}
    private enum Phase { QUERIED, CHECKING, READY, DOWNLOADING, WAITING, LOCAL, SERVER, UNAVAILABLE }
    private record State(Phase phase, String hash, String serverId, long size, String detail) {
        static State of(Phase phase) { return new State(phase, "", "", 0, ""); }
    }
    private static final class Incoming {
        final String modelId;
        final State offer;
        final Path spool;
        final OutputStream out;
        long received;
        Incoming(String modelId, State offer, Path spool, OutputStream out) {
            this.modelId = modelId; this.offer = offer; this.spool = spool; this.out = out;
        }
    }
    private static final int MAX_TRACKED = 512;
    // Leave ample space below the server's 64 queued downloads per player.
    private static final TlmDownloadWindow DOWNLOADS = new TlmDownloadWindow(8);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ysm-geo-pack-client");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger SESSION = new AtomicInteger();
    private static final Map<String, State> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, String> ACTIVE_HASHES = new HashMap<>(); // client thread only
    private static final TlmPackRefreshSchedule REFRESH = new TlmPackRefreshSchedule();
    private static final Map<String, Incoming> INCOMING = new HashMap<>(); // IO thread only
    private static final Map<String, Path> MATERIALIZED = new HashMap<>(); // IO thread only
    private static final ConcurrentLinkedQueue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    private static boolean tlmDirty;
    private static boolean meshDirty;
    private static final AtomicBoolean REFRESH_REQUESTED = new AtomicBoolean();

    private TlmPackClient() {}

    /** Called as maids are rendered; one query per model and connection. */
    public static void requestIfNeeded(String modelId) {
        if (!downloadsEnabled() || !TlmPackCatalog.validModelId(modelId) || MODELS.size() >= MAX_TRACKED) return;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !TlmPackNetwork.peerSupports(connection.getConnection())) return;
        if (MODELS.putIfAbsent(modelId, State.of(Phase.QUERIED)) == null) {
            REFRESH.track(modelId, System.nanoTime());
            TlmPackNetwork.sendToServer(new TlmPackNetwork.Query(modelId));
        }
    }

    /** Read-only render-thread status for explaining a missing converted mesh. */
    public static Resolution resolution(String modelId) {
        State state = MODELS.get(modelId);
        if (state != null) {
            return switch (state.phase()) {
                case QUERIED, CHECKING, READY, DOWNLOADING, WAITING -> new Resolution(true, false, null);
                case LOCAL, SERVER -> new Resolution(false, true, null);
                case UNAVAILABLE -> new Resolution(false, false, state.detail());
            };
        }
        if (!TlmPackCatalog.validModelId(modelId)) {
            return new Resolution(false, false, "invalid model id");
        }
        if (!downloadsEnabled()) {
            return new Resolution(false, false, "client downloadServerModels is disabled");
        }
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !TlmPackNetwork.peerSupports(connection.getConnection())) {
            return new Resolution(false, false, "server has no compatible TLM pack transfer channel");
        }
        if (MODELS.size() >= MAX_TRACKED) {
            return new Resolution(false, false, "model query limit reached for this connection");
        }
        return new Resolution(false, false, "server model query has not started");
    }

    /** Forge dispatches this on the client thread. */
    public static void onInfo(TlmPackNetwork.Info info, Connection source) {
        if (!currentConnection(source)) return;
        State current = MODELS.get(info.modelId());
        if (current == null) return;
        DOWNLOADS.remove(info.modelId());
        if (current.phase() == Phase.DOWNLOADING) wakeWaiters(current);
        if (info.status() != TlmPackNetwork.Status.AVAILABLE) {
            IO.execute(() -> abort(info.modelId()));
            boolean hadServerCopy = ACTIVE_HASHES.containsKey(info.modelId());
            if (info.status() == TlmPackNetwork.Status.NOT_FOUND
                    || info.status() == TlmPackNetwork.Status.DISABLED) {
                forgetServerCopy(info.modelId());
            }
            if (info.status() == TlmPackNetwork.Status.NOT_FOUND
                    && !hadServerCopy && TlmModelLibrary.find(info.modelId()) != null) {
                MODELS.put(info.modelId(), State.of(Phase.LOCAL));
                REFRESH.track(info.modelId(), System.nanoTime());
                return;
            }
            unavailable(info.modelId(), switch (info.status()) {
                case NOT_FOUND -> "server has no TLM pack declaring this model";
                case DISABLED -> "server modelTransferEnabled is disabled";
                case TOO_LARGE -> "server pack exceeds its configured transfer size";
                case FAILED -> "server could not read or prepare the pack";
                case AVAILABLE -> throw new IllegalStateException("available status handled above");
            });
            return;
        }
        if (current.phase() == Phase.DOWNLOADING) IO.execute(() -> abort(info.modelId()));
        if (!validServerId(info.serverId()) || !TlmPackArchive.validSha256(info.sha256())
                || info.size() <= 0 || info.size() > maxBytes()) {
            unavailable(info.modelId(), "malformed or oversized offer");
            return;
        }
        State offer = new State(Phase.CHECKING, info.sha256(), info.serverId(), info.size(), "");
        MODELS.put(info.modelId(), offer);
        int session = SESSION.get();
        IO.execute(() -> check(session, info.modelId(), offer));
    }

    /** Chunk processing is serialized on one IO thread and never blocks a network or render thread. */
    public static void onChunk(TlmPackNetwork.Chunk chunk, Connection source) {
        if (!currentConnection(source)) return;
        int session = SESSION.get();
        IO.execute(() -> append(session, chunk));
    }

    public static void onResourceReload() {
        if (!TlmServerOverlay.activePacks().isEmpty()) tlmDirty = true;
        REFRESH_REQUESTED.set(true);
    }

    public static void clear() {
        SESSION.incrementAndGet();
        MODELS.clear();
        ACTIVE_HASHES.clear();
        DOWNLOADS.clear();
        REFRESH.clear();
        REFRESH_REQUESTED.set(false);
        MAIN.clear();
        IO.execute(() -> {
            for (String modelId : java.util.List.copyOf(INCOMING.keySet())) abort(modelId);
            MATERIALIZED.clear();
        });
        if (TlmServerOverlay.clear()) {
            tlmDirty = true;
            meshDirty = true;
        }
    }

    /** Run between frames after all queued packet and IO callbacks have settled. */
    public static void tick() {
        Runnable action;
        while ((action = MAIN.poll()) != null) {
            try { action.run(); }
            catch (Throwable error) { YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: pack update failed", error); }
        }
        if (REFRESH_REQUESTED.getAndSet(false)) {
            REFRESH.refreshNow();
        }
        var connection = Minecraft.getInstance().getConnection();
        if (downloadsEnabled() && connection != null
                && TlmPackNetwork.peerSupports(connection.getConnection())) {
            // Bound both network traffic and server-side pack scans after F3+T.
            for (int i = 0; i < 2; i++) {
                String due = REFRESH.nextDue(System.nanoTime(), id -> {
                    State state = MODELS.get(id);
                    return state != null && (state.phase() == Phase.LOCAL
                            || state.phase() == Phase.SERVER || state.phase() == Phase.UNAVAILABLE);
                });
                if (due == null) break;
                TlmPackNetwork.sendToServer(new TlmPackNetwork.Query(due));
            }
        }
        String next;
        while ((next = DOWNLOADS.poll()) != null) {
            State ready = MODELS.get(next);
            if (ready == null || ready.phase() != Phase.READY) {
                DOWNLOADS.remove(next);
                continue;
            }
            MODELS.put(next, new State(Phase.DOWNLOADING, ready.hash(), ready.serverId(), ready.size(), ""));
            TlmPackNetwork.sendToServer(new TlmPackNetwork.Download(next, ready.hash()));
        }
        if (tlmDirty) {
            tlmDirty = false;
            if (ModList.get().isLoaded("touhou_little_maid")) refreshTlm();
        }
        if (meshDirty) {
            meshDirty = false;
            YSMCompatClientEvents.regenerateMeshes(Minecraft.getInstance().getResourceManager());
        }
    }

    private static void check(int session, String modelId, State offer) {
        if (session != SESSION.get()) return;
        try {
            Path root = cacheRoot(offer.serverId());
            Path cached = root.resolve(offer.hash() + ".zip");
            if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)
                    && TlmPackArchive.sha256(cached, maxBytes()).equals(offer.hash())) {
                Path unpacked = materialized(offer.serverId(), offer.hash(), cached);
                post(session, () -> {
                    if (MODELS.get(modelId) == offer) activate(modelId, offer, unpacked);
                });
                return;
            }
            Path local = TlmPackCatalog.find(Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("tlm_custom_pack"), modelId);
            if (local != null && TlmPackArchive.sha256(TlmPackArchive.packForTransfer(local, maxBytes())).equals(offer.hash())) {
                post(session, () -> {
                    if (MODELS.get(modelId) != offer) return;
                    MODELS.put(modelId, State.of(Phase.LOCAL));
                    REFRESH.track(modelId, System.nanoTime());
                    forgetServerCopy(modelId);
                });
                return;
            }
        } catch (Exception e) {
            // A missing/broken local copy or cache is still recoverable by downloading.
            YSMGeoCompat.LOGGER.debug("YSM-GEO Compat: local TLM pack check for '{}' failed: {}", modelId, e.toString());
        }
        post(session, () -> {
            if (MODELS.get(modelId) != offer) return;
            Path active = TlmServerOverlay.activePack(offer.hash());
            if (active != null) {
                activate(modelId, offer, active);
                return;
            }
            for (State pending : MODELS.values()) {
                if ((pending.phase() == Phase.READY || pending.phase() == Phase.DOWNLOADING)
                        && pending.hash().equals(offer.hash())
                        && pending.serverId().equals(offer.serverId())) {
                    MODELS.put(modelId, new State(Phase.WAITING, offer.hash(), offer.serverId(), offer.size(), ""));
                    return;
                }
            }
            MODELS.put(modelId, new State(Phase.READY, offer.hash(), offer.serverId(), offer.size(), ""));
            DOWNLOADS.enqueue(modelId);
        });
    }

    private static void append(int session, TlmPackNetwork.Chunk chunk) {
        if (session != SESSION.get()) return;
        String modelId = chunk.modelId();
        State state = MODELS.get(modelId);
        if (state == null || state.phase() != Phase.DOWNLOADING) return;
        Incoming incoming = INCOMING.get(modelId);
        try {
            if (incoming == null) {
                if (chunk.offset() != 0) throw new IOException("first chunk has a nonzero offset");
                Path root = cacheRoot(state.serverId());
                Path spool = Files.createTempFile(root, ".download-", ".part");
                incoming = new Incoming(modelId, state, spool, Files.newOutputStream(spool));
                INCOMING.put(modelId, incoming);
            }
            if (chunk.total() != incoming.offer.size() || chunk.offset() != incoming.received
                    || chunk.data().length == 0 || chunk.data().length > incoming.offer.size() - incoming.received) {
                throw new IOException("out-of-order or oversized chunk");
            }
            incoming.out.write(chunk.data());
            incoming.received += chunk.data().length;
            if (incoming.received == incoming.offer.size()) complete(session, incoming);
        } catch (Exception e) {
            abort(modelId);
            post(session, () -> {
                if (MODELS.get(modelId) == state) unavailable(modelId, "download failed: " + e.getMessage());
            });
        }
    }

    private static void complete(int session, Incoming incoming) throws IOException {
        incoming.out.close();
        INCOMING.remove(incoming.modelId);
        try {
            if (!TlmPackArchive.sha256(incoming.spool, maxBytes()).equals(incoming.offer.hash())) {
                throw new IOException("SHA-256 mismatch");
            }
            Path target = cacheRoot(incoming.offer.serverId()).resolve(incoming.offer.hash() + ".zip");
            try {
                Files.move(incoming.spool, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(incoming.spool, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Path unpacked = materialized(incoming.offer.serverId(), incoming.offer.hash(), target);
            post(session, () -> {
                if (MODELS.get(incoming.modelId) == incoming.offer) {
                    activate(incoming.modelId, incoming.offer, unpacked);
                }
            });
        } finally {
            Files.deleteIfExists(incoming.spool);
        }
    }

    private static void activate(String modelId, State offer, Path cached) {
        DOWNLOADS.remove(modelId);
        MODELS.put(modelId, new State(Phase.SERVER, offer.hash(), offer.serverId(), offer.size(), ""));
        ACTIVE_HASHES.put(modelId, offer.hash());
        MODELS.replaceAll((id, state) -> state.phase() == Phase.WAITING
                && state.hash().equals(offer.hash()) && state.serverId().equals(offer.serverId())
                ? new State(Phase.SERVER, offer.hash(), offer.serverId(), offer.size(), "") : state);
        MODELS.forEach((id, state) -> {
            if (state.phase() == Phase.SERVER && state.hash().equals(offer.hash())) {
                ACTIVE_HASHES.put(id, offer.hash());
                REFRESH.track(id, System.nanoTime());
            }
        });
        boolean changed = TlmServerOverlay.activate(offer.hash(), cached);
        changed |= TlmServerOverlay.retainHashes(Set.copyOf(ACTIVE_HASHES.values()));
        if (changed) {
            YSMGeoCompat.LOGGER.info("YSM-GEO Compat: using server TLM pack for '{}'", modelId);
            tlmDirty = true;
            meshDirty = true;
        }
    }

    private static Path materialized(String serverId, String hash, Path zip) throws IOException {
        String key = serverId + "/" + hash;
        Path existing = MATERIALIZED.get(key);
        if (existing != null && Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) return existing;
        Path folder = TlmPackArchive.materialize(zip, cacheRoot(serverId), hash,
                maxBytes(), TlmPackArchive.expandedLimit(maxBytes()));
        MATERIALIZED.put(key, folder);
        return folder;
    }

    private static void unavailable(String modelId, String reason) {
        State prior = MODELS.get(modelId);
        DOWNLOADS.remove(modelId);
        MODELS.put(modelId, new State(Phase.UNAVAILABLE, "", "", 0, reason));
        REFRESH.track(modelId, System.nanoTime());
        if (prior != null && (prior.phase() == Phase.READY || prior.phase() == Phase.DOWNLOADING)) {
            MODELS.replaceAll((id, state) -> state.phase() == Phase.WAITING
                    && state.hash().equals(prior.hash()) && state.serverId().equals(prior.serverId())
                    ? new State(Phase.UNAVAILABLE, "", "", 0, reason) : state);
        }
        YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: server TLM model '{}' unavailable ({}); using local/EFTLM fallback",
                modelId, reason);
    }

    private static void forgetServerCopy(String modelId) {
        if (ACTIVE_HASHES.remove(modelId) != null
                && TlmServerOverlay.retainHashes(Set.copyOf(ACTIVE_HASHES.values()))) {
            tlmDirty = true;
            meshDirty = true;
        }
    }

    private static void wakeWaiters(State oldOffer) {
        MODELS.replaceAll((id, state) -> {
            if (state.phase() != Phase.WAITING || !state.hash().equals(oldOffer.hash())
                    || !state.serverId().equals(oldOffer.serverId())) return state;
            REFRESH.track(id, System.nanoTime());
            return new State(Phase.UNAVAILABLE, "", "", 0, "server pack changed during download");
        });
    }

    private static void refreshTlm() {
        try {
            TlmClientPackBridge.reloadWithOverlays();
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: TLM pack registry reload failed", e);
        }
    }

    private static Path cacheRoot(String serverId) throws IOException {
        if (!validServerId(serverId)) throw new IOException("invalid server id");
        Path base = Minecraft.getInstance().gameDirectory.toPath().resolve("config/ysm_geo_compat/server_models");
        Path root = base.resolve(serverId);
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(base.toRealPath()) || Files.isSymbolicLink(root)) {
            throw new IOException("linked server cache directory");
        }
        return root;
    }

    private static boolean validServerId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static void abort(String modelId) {
        Incoming incoming = INCOMING.remove(modelId);
        if (incoming == null) return;
        try { incoming.out.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(incoming.spool); } catch (IOException ignored) {}
    }

    private static void post(int session, Runnable action) {
        MAIN.add(() -> { if (session == SESSION.get()) action.run(); });
    }

    private static boolean downloadsEnabled() {
        try { return YSMCompatConfig.DOWNLOAD_SERVER_MODELS.get(); }
        catch (IllegalStateException ignored) { return true; }
    }

    private static boolean currentConnection(Connection source) {
        var client = Minecraft.getInstance().getConnection();
        return source != null && client != null && client.getConnection() == source;
    }

    private static long maxBytes() {
        try { return YSMCompatConfig.MAX_DOWNLOADED_MODEL_MIB.get() * 1024L * 1024L; }
        catch (IllegalStateException ignored) { return 64L * 1024 * 1024; }
    }
}
