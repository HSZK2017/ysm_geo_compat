package com.ysmef.geomodel.network;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.config.YSMCompatConfig;
import com.ysmef.geomodel.model.TlmPackArchive;
import com.ysmef.geomodel.model.TlmPackCatalog;
import com.ysmef.geomodel.network.TlmPackedPackCache.Offer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Offers TLM custom packs by model id and streams only explicitly requested versions. */
@Mod.EventBusSubscriber(modid = YSMGeoCompat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TlmPackServer {
    private static final int MAX_QUEUED = 64;
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final byte[] HEARTBEAT = new byte[0];
    private static final Path PACK_FOLDER = Path.of("tlm_custom_pack");
    private static final Path SERVER_ID_FILE = Path.of("config", "ysm_geo_compat", "server_id.txt");
    private static final ThreadPoolExecutor QUERY_IO = createIoExecutor("ysm-geo-pack-query", 64);
    private static final ThreadPoolExecutor DOWNLOAD_IO = createIoExecutor("ysm-geo-pack-download", 8);

    // Captured by queued jobs so work from a stopped server cannot refill its replacement.
    private static TlmPackedPackCache packs = new TlmPackedPackCache(System::nanoTime);
    private static final class Download {
        final String modelId;
        final Offer offer;
        final Runnable loadTask;
        volatile boolean cancelled;
        volatile byte[] bytes;
        volatile boolean failed;
        volatile boolean changed;
        int offset;
        long lastHeartbeatNanos;
        Download(String modelId, Offer offer, TlmPackedPackCache cache) {
            this.modelId = modelId;
            this.offer = offer;
            this.loadTask = () -> load(this, cache);
            this.lastHeartbeatNanos = System.nanoTime();
        }
    }
    private static final class PlayerState {
        final Connection connection;
        final TlmPackQueryQueue queries = new TlmPackQueryQueue();
        final Set<String> requested = new HashSet<>();
        final Map<String, Offer> offered = new HashMap<>();
        final ArrayDeque<Download> queue = new ArrayDeque<>();
        Runnable queryTask;
        Download active;

        PlayerState(Connection connection) {
            this.connection = connection;
        }
    }
    // The server thread owns connection state. IO workers publish through server.execute.
    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
    private static int activeCount;
    private static volatile String serverId;

    private TlmPackServer() {}

    private static ThreadPoolExecutor createIoExecutor(String name, int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static void onQuery(ServerPlayer player, String modelId) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        Connection connection = player.connection.connection;
        if (!TlmPackNetwork.peerSupports(connection) || !TlmPackCatalog.validModelId(modelId)) return;
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state == null || state.connection != connection) {
            if (state != null) release(state);
            state = new PlayerState(connection);
            PLAYERS.put(player.getUUID(), state);
        }
        state.queries.request(modelId, System.nanoTime());
    }

    public static void onDownload(ServerPlayer player, String modelId, String hash) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state == null || state.connection != player.connection.connection
                || !enabled() || !TlmPackArchive.validSha256(hash)) return;
        Offer offer = state.offered.get(modelId);
        if (offer == null || !offer.hash().equals(hash)) return;
        String requestKey = modelId + "\u0000" + hash;
        if (state.requested.contains(requestKey)) return;
        if (state.active != null && state.active.modelId.equals(modelId)) return;
        for (Download queued : state.queue) if (queued.modelId.equals(modelId)) return;
        if (state.queue.size() < MAX_QUEUED) {
            state.requested.add(requestKey);
            state.queue.add(new Download(modelId, offer, packs));
        } else {
            // Every accepted offer must get either chunks or a terminal answer.
            reply(player, new TlmPackNetwork.Info(modelId, TlmPackNetwork.Status.FAILED, "", 0, ""));
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        if (server.getTickCount() % 20 == 0) packs.expire(System.nanoTime(), maxBytes());
        if (PLAYERS.isEmpty()) return;
        int budget = kibPerTick() * 1024;
        Iterator<Map.Entry<UUID, PlayerState>> it = PLAYERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlayerState> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerState state = entry.getValue();
            if (player == null || state.connection != player.connection.connection
                    || !TlmPackNetwork.peerSupports(state.connection)) {
                release(state);
                it.remove();
                continue;
            }
            long now = System.nanoTime();
            dispatchQuery(player, state, now);
            for (Download queued : state.queue) heartbeat(player, queued, now);
            Download transfer = state.active;
            if (transfer == null) {
                if (state.queue.isEmpty() || activeCount >= maxConcurrent()) continue;
                transfer = state.queue.peek();
                try {
                    DOWNLOAD_IO.execute(transfer.loadTask);
                } catch (RejectedExecutionException full) {
                    continue; // Keep the queued transfer and its heartbeats until a worker is free.
                }
                state.active = state.queue.remove();
                activeCount++;
                continue;
            }
            if (transfer.failed) {
                if (transfer.changed) {
                    state.offered.remove(transfer.modelId);
                    state.queries.request(transfer.modelId, now);
                } else {
                    reply(player, new TlmPackNetwork.Info(transfer.modelId, TlmPackNetwork.Status.FAILED, "", 0, ""));
                }
                finish(state);
                continue;
            }
            byte[] bytes = transfer.bytes;
            if (bytes == null) {
                heartbeat(player, transfer, now);
                continue;
            }
            int sent = 0;
            while (sent < budget && transfer.offset < bytes.length) {
                int length = Math.min(Math.min(TlmPackNetwork.CHUNK_BYTES, budget - sent), bytes.length - transfer.offset);
                reply(player, new TlmPackNetwork.Chunk(transfer.modelId, transfer.offset, bytes.length,
                        bytes, transfer.offset, length));
                transfer.offset += length;
                sent += length;
            }
            if (transfer.offset == bytes.length) finish(state);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state != null && state.connection == player.connection.connection) {
            PLAYERS.remove(player.getUUID());
            release(state);
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        PLAYERS.values().forEach(TlmPackServer::release);
        PLAYERS.clear();
        packs = new TlmPackedPackCache(System::nanoTime);
        activeCount = 0;
    }

    private static void dispatchQuery(ServerPlayer player, PlayerState state, long now) {
        String modelId = state.queries.poll(now);
        if (modelId == null) return;
        boolean sharing = enabled();
        if (!sharing || player.server.isSingleplayerOwner(player.getGameProfile())) {
            TlmPackNetwork.Status status = sharing ? TlmPackNetwork.Status.NOT_FOUND : TlmPackNetwork.Status.DISABLED;
            resetTransferForQuery(state, modelId);
            state.queries.complete(now);
            reply(player, new TlmPackNetwork.Info(modelId, status, "", 0, ""));
            return;
        }
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        TlmPackedPackCache cache = packs;
        Runnable task = () -> answerAsync(server, playerId, state, modelId, cache);
        try {
            QUERY_IO.execute(task);
        } catch (RejectedExecutionException full) {
            state.queries.retry();
            return;
        }
        state.queryTask = task;
        resetTransferForQuery(state, modelId);
    }

    private static void answerAsync(MinecraftServer server, UUID playerId, PlayerState state, String modelId,
                                    TlmPackedPackCache cache) {
        if (state.queries.isClosed()) return;
        Offer offer = null;
        TlmPackNetwork.Status status;
        try {
            Path found = TlmPackCatalog.find(PACK_FOLDER, modelId);
            if (found == null) {
                status = TlmPackNetwork.Status.NOT_FOUND;
            } else {
                offer = cache.describe(found, maxBytes());
                status = TlmPackNetwork.Status.AVAILABLE;
            }
        } catch (Exception e) {
            status = e.getMessage() != null && e.getMessage().contains("large")
                    ? TlmPackNetwork.Status.TOO_LARGE : TlmPackNetwork.Status.FAILED;
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: could not offer TLM model '{}': {}", modelId, e.toString());
        }
        if (state.queries.isClosed()) return;
        Offer result = offer;
        TlmPackNetwork.Status answer = status;
        String serverIdentity = result == null ? "" : serverId();
        server.execute(() -> {
            if (PLAYERS.get(playerId) != state || state.queries.isClosed()) return;
            state.queryTask = null;
            state.queries.complete(System.nanoTime());
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || player.connection.connection != state.connection) return;
            if (result != null) state.offered.put(modelId, result);
            reply(player, new TlmPackNetwork.Info(modelId, answer,
                    serverIdentity, result == null ? 0 : result.size(), result == null ? "" : result.hash()));
        });
    }

    private static void load(Download transfer, TlmPackedPackCache cache) {
        if (transfer.cancelled) return;
        try {
            TlmPackedPackCache.Packed packed = cache.load(transfer.offer.source(), maxBytes());
            if (!packed.offer().hash().equals(transfer.offer.hash())) {
                transfer.changed = true;
                transfer.failed = true;
                return;
            }
            if (!transfer.cancelled) transfer.bytes = packed.bytes();
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: could not send TLM model '{}': {}", transfer.modelId, e.toString());
            transfer.failed = true;
        }
    }

    private static void finish(PlayerState state) {
        if (state.active == null) return;
        state.active.cancelled = true;
        DOWNLOAD_IO.remove(state.active.loadTask);
        state.active.bytes = null;
        state.active = null;
        activeCount = Math.max(0, activeCount - 1);
    }

    private static void resetTransferForQuery(PlayerState state, String modelId) {
        // A new query replaces any offer or transfer the client abandoned.
        state.offered.remove(modelId);
        if (state.active != null && state.active.modelId.equals(modelId)) finish(state);
        state.queue.removeIf(download -> download.modelId.equals(modelId));
        String prefix = modelId + "\u0000";
        state.requested.removeIf(key -> key.startsWith(prefix));
    }

    private static void heartbeat(ServerPlayer player, Download transfer, long now) {
        if (now - transfer.lastHeartbeatNanos < HEARTBEAT_NANOS) return;
        transfer.lastHeartbeatNanos = now;
        reply(player, new TlmPackNetwork.Chunk(transfer.modelId, 0, transfer.offer.size(), HEARTBEAT));
    }

    private static void release(PlayerState state) {
        state.queries.close();
        if (state.queryTask != null) {
            QUERY_IO.remove(state.queryTask);
            state.queryTask = null;
        }
        finish(state);
        state.queue.clear();
    }

    private static void reply(ServerPlayer player, Object packet) {
        TlmPackNetwork.sendToPlayer(player, packet);
    }

    private static boolean enabled() {
        try { return YSMCompatConfig.MODEL_TRANSFER_ENABLED.get(); }
        catch (IllegalStateException ignored) { return true; }
    }
    private static long maxBytes() {
        try { return YSMCompatConfig.MODEL_TRANSFER_MAX_MODEL_MIB.get() * 1024L * 1024L; }
        catch (IllegalStateException ignored) { return 64L * 1024 * 1024; }
    }
    private static int kibPerTick() {
        try { return YSMCompatConfig.MODEL_TRANSFER_KIB_PER_TICK.get(); }
        catch (IllegalStateException ignored) { return 64; }
    }
    private static int maxConcurrent() {
        try { return YSMCompatConfig.MODEL_TRANSFER_MAX_CONCURRENT.get(); }
        catch (IllegalStateException ignored) { return 2; }
    }

    private static String serverId() {
        if (serverId != null) return serverId;
        synchronized (TlmPackServer.class) {
            if (serverId != null) return serverId;
            try {
                if (Files.isRegularFile(SERVER_ID_FILE)) {
                    String stored = Files.readString(SERVER_ID_FILE, StandardCharsets.UTF_8).trim();
                    if (validServerId(stored)) return serverId = stored;
                }
                String created = UUID.randomUUID().toString();
                Files.createDirectories(SERVER_ID_FILE.getParent());
                Files.writeString(SERVER_ID_FILE, created, StandardCharsets.UTF_8);
                return serverId = created;
            } catch (Exception e) {
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: could not persist server pack id: {}", e.toString());
                return serverId = UUID.randomUUID().toString();
            }
        }
    }

    public static boolean validServerId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
