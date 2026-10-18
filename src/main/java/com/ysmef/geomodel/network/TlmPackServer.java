package com.ysmef.geomodel.network;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.config.YSMCompatConfig;
import com.ysmef.geomodel.model.TlmPackArchive;
import com.ysmef.geomodel.model.TlmPackCatalog;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Offers TLM custom packs by model id and streams only explicitly requested versions. */
@Mod.EventBusSubscriber(modid = YSMGeoCompat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TlmPackServer {
    private static final int MAX_QUERIES = 512;
    private static final int MAX_QUEUED = 64;
    private static final long MIN_REQUERY_NANOS = TimeUnit.SECONDS.toNanos(5);
    // Shorter than the client's poll interval, so every periodic query verifies content.
    private static final long HASH_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Path PACK_FOLDER = Path.of("tlm_custom_pack");
    private static final Path SERVER_ID_FILE = Path.of("config", "ysm_geo_compat", "server_id.txt");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ysm-geo-pack-server");
        thread.setDaemon(true);
        return thread;
    });

    private record Offer(Path source, String hash, long size) {}
    private record CachedOffer(long signature, long checkedAtNanos, Offer offer) {}
    private static final Map<Path, CachedOffer> OFFERS = new ConcurrentHashMap<>();
    private static final class Download {
        final String modelId;
        final Offer offer;
        volatile byte[] bytes;
        volatile boolean failed;
        volatile boolean changed;
        int offset;
        Download(String modelId, Offer offer) { this.modelId = modelId; this.offer = offer; }
    }
    private static final class PlayerState {
        final Map<String, Long> queried = new HashMap<>();
        final Map<String, Long> pendingQueries = new HashMap<>();
        final Set<String> requested = new HashSet<>();
        final Map<String, Offer> offered = new HashMap<>();
        final ArrayDeque<Download> queue = new ArrayDeque<>();
        Download active;
    }
    private static final Map<UUID, PlayerState> PLAYERS = new ConcurrentHashMap<>();
    private static int activeCount;
    private static volatile String serverId;

    private TlmPackServer() {}

    public static void onQuery(ServerPlayer player, String modelId) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        if (!TlmPackNetwork.peerSupports(player.connection.connection)) return;
        PlayerState state = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        if (!TlmPackCatalog.validModelId(modelId)) return;
        long now = System.nanoTime();
        Long last = state.queried.get(modelId);
        if (last == null && state.queried.size() >= MAX_QUERIES) return;
        if (last != null && now - last < MIN_REQUERY_NANOS) {
            state.pendingQueries.put(modelId, last + MIN_REQUERY_NANOS);
            return;
        }
        state.pendingQueries.remove(modelId);
        state.queried.put(modelId, now);
        answerQuery(player, modelId);
    }

    private static void answerQuery(ServerPlayer player, String modelId) {
        if (!enabled()) {
            reply(player, new TlmPackNetwork.Info(modelId, TlmPackNetwork.Status.DISABLED, "", 0, ""));
            return;
        }
        if (player.server.isSingleplayerOwner(player.getGameProfile())) {
            reply(player, new TlmPackNetwork.Info(modelId, TlmPackNetwork.Status.NOT_FOUND, "", 0, ""));
            return;
        }
        answerAsync(player.server, player.getUUID(), modelId);
    }

    public static void onDownload(ServerPlayer player, String modelId, String hash) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state == null || !enabled() || !TlmPackArchive.validSha256(hash)) return;
        Offer offer = state.offered.get(modelId);
        if (offer == null || !offer.hash().equals(hash)) return;
        String requestKey = modelId + "\u0000" + hash;
        if (state.requested.contains(requestKey)) return;
        if (state.active != null && state.active.modelId.equals(modelId)) return;
        for (Download queued : state.queue) if (queued.modelId.equals(modelId)) return;
        if (state.queue.size() < MAX_QUEUED) {
            state.requested.add(requestKey);
            state.queue.add(new Download(modelId, offer));
        } else {
            // Every accepted offer must get either chunks or a terminal answer.
            reply(player, new TlmPackNetwork.Info(modelId, TlmPackNetwork.Status.FAILED, "", 0, ""));
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PLAYERS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        int budget = kibPerTick() * 1024;
        Iterator<Map.Entry<UUID, PlayerState>> it = PLAYERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlayerState> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerState state = entry.getValue();
            if (player == null || !TlmPackNetwork.peerSupports(player.connection.connection)) {
                release(state);
                it.remove();
                continue;
            }
            long now = System.nanoTime();
            int refreshed = 0;
            Iterator<Map.Entry<String, Long>> queries = state.pendingQueries.entrySet().iterator();
            while (queries.hasNext() && refreshed < 2) {
                Map.Entry<String, Long> query = queries.next();
                if (now < query.getValue()) continue;
                String modelId = query.getKey();
                queries.remove();
                state.queried.put(modelId, now);
                answerQuery(player, modelId);
                refreshed++;
            }
            Download transfer = state.active;
            if (transfer == null) {
                if (state.queue.isEmpty() || activeCount >= maxConcurrent()) continue;
                transfer = state.queue.poll();
                state.active = transfer;
                activeCount++;
                Download loading = transfer;
                IO.execute(() -> load(loading));
                continue;
            }
            if (transfer.failed) {
                if (transfer.changed) {
                    // Size and mtime can remain unchanged across a replacement.
                    // The full hash detected a new version, so discard the cheap
                    // signature cache before preparing a fresh offer.
                    OFFERS.remove(transfer.offer.source());
                    answerAsync(server, entry.getKey(), transfer.modelId);
                }
                else reply(player, new TlmPackNetwork.Info(transfer.modelId, TlmPackNetwork.Status.FAILED, "", 0, ""));
                finish(state);
                continue;
            }
            byte[] bytes = transfer.bytes;
            if (bytes == null) continue;
            int sent = 0;
            while (sent < budget && transfer.offset < bytes.length) {
                int length = Math.min(Math.min(TlmPackNetwork.CHUNK_BYTES, budget - sent), bytes.length - transfer.offset);
                reply(player, new TlmPackNetwork.Chunk(transfer.modelId, transfer.offset, bytes.length,
                        Arrays.copyOfRange(bytes, transfer.offset, transfer.offset + length)));
                transfer.offset += length;
                sent += length;
            }
            if (transfer.offset == bytes.length) finish(state);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerState state = PLAYERS.remove(event.getEntity().getUUID());
        if (state != null) release(state);
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        PLAYERS.clear();
        OFFERS.clear();
        activeCount = 0;
    }

    private static void answerAsync(MinecraftServer server, UUID playerId, String modelId) {
        PlayerState expectedState = PLAYERS.get(playerId);
        if (expectedState == null) return;
        IO.execute(() -> {
            Offer offer = null;
            TlmPackNetwork.Status status;
            try {
                Path found = TlmPackCatalog.find(PACK_FOLDER, modelId);
                if (found == null) {
                    status = TlmPackNetwork.Status.NOT_FOUND;
                } else {
                    long signature = TlmPackArchive.signature(found);
                    CachedOffer cached = OFFERS.get(found);
                    long now = System.nanoTime();
                    if (cached == null || cached.signature() != signature
                            || now - cached.checkedAtNanos() >= HASH_REFRESH_NANOS) {
                        byte[] bytes = TlmPackArchive.packForTransfer(found, maxBytes());
                        cached = new CachedOffer(signature, now,
                                new Offer(found, TlmPackArchive.sha256(bytes), bytes.length));
                        if (OFFERS.size() >= 256) OFFERS.clear();
                        OFFERS.put(found, cached);
                    }
                    offer = cached.offer();
                    status = TlmPackNetwork.Status.AVAILABLE;
                }
            } catch (Exception e) {
                status = e.getMessage() != null && e.getMessage().contains("large")
                        ? TlmPackNetwork.Status.TOO_LARGE : TlmPackNetwork.Status.FAILED;
                YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: could not offer TLM model '{}': {}", modelId, e.toString());
            }
            Offer result = offer;
            TlmPackNetwork.Status answer = status;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                PlayerState state = PLAYERS.get(playerId);
                if (player == null || state != expectedState) return;
                if (result != null) state.offered.put(modelId, result);
                else state.offered.remove(modelId);
                reply(player, new TlmPackNetwork.Info(modelId, answer,
                        result == null ? "" : serverId(), result == null ? 0 : result.size(),
                        result == null ? "" : result.hash()));
            });
        });
    }

    private static void load(Download transfer) {
        try {
            byte[] bytes = TlmPackArchive.packForTransfer(transfer.offer.source(), maxBytes());
            if (!TlmPackArchive.sha256(bytes).equals(transfer.offer.hash())) {
                transfer.changed = true;
                transfer.failed = true;
                return;
            }
            transfer.bytes = bytes;
        } catch (Exception e) {
            YSMGeoCompat.LOGGER.warn("YSM-GEO Compat: could not send TLM model '{}': {}", transfer.modelId, e.toString());
            transfer.failed = true;
        }
    }

    private static void finish(PlayerState state) {
        if (state.active == null) return;
        state.active.bytes = null;
        state.active = null;
        activeCount = Math.max(0, activeCount - 1);
    }

    private static void release(PlayerState state) {
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
