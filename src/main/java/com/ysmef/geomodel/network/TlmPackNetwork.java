package com.ysmef.geomodel.network;

import com.ysmef.geomodel.YSMGeoCompat;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Optional protocol: neither side sends packets to peers without the same channel version. */
public final class TlmPackNetwork {
    // Zero-length chunks are transfer heartbeats; older peers must not treat them as failed data.
    public static final String VERSION = "1.1.0";
    public static final int CHUNK_BYTES = 32 * 1024;
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(YSMGeoCompat.MODID, "tlm_pack_transfer");
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ID, () -> VERSION,
            remote -> true, remote -> true);

    public enum Status { AVAILABLE, NOT_FOUND, DISABLED, TOO_LARGE, FAILED }
    public record Query(String modelId) {}
    public record Info(String modelId, Status status, String serverId, long size, String sha256) {}
    public record Download(String modelId, String sha256) {}
    public record Chunk(String modelId, long offset, long total, byte[] data) {}

    private TlmPackNetwork() {}

    public static void init() {
        CHANNEL.registerMessage(0, Query.class, (m, b) -> b.writeUtf(m.modelId()),
                b -> new Query(b.readUtf(256)), TlmPackNetwork::handleQuery,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Info.class, TlmPackNetwork::writeInfo, TlmPackNetwork::readInfo,
                TlmPackNetwork::handleInfo, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Download.class, (m, b) -> {
                    b.writeUtf(m.modelId()); b.writeUtf(m.sha256());
                }, b -> new Download(b.readUtf(256), b.readUtf(64)), TlmPackNetwork::handleDownload,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, Chunk.class, (m, b) -> {
                    b.writeUtf(m.modelId()); b.writeLong(m.offset()); b.writeLong(m.total()); b.writeByteArray(m.data());
                }, b -> new Chunk(b.readUtf(256), b.readLong(), b.readLong(), b.readByteArray(CHUNK_BYTES)),
                TlmPackNetwork::handleChunk, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static boolean peerSupports(Connection connection) {
        if (connection == null) return false;
        try {
            var data = NetworkHooks.getConnectionData(connection);
            return data != null && VERSION.equals(data.getChannels().get(ID));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void sendToServer(Object message) { CHANNEL.sendToServer(message); }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        if (peerSupports(player.connection.connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    private static void writeInfo(Info info, FriendlyByteBuf out) {
        out.writeUtf(info.modelId());
        out.writeEnum(info.status());
        out.writeUtf(info.serverId());
        out.writeLong(info.size());
        out.writeUtf(info.sha256());
    }

    private static Info readInfo(FriendlyByteBuf in) {
        return new Info(in.readUtf(256), in.readEnum(Status.class), in.readUtf(64), in.readLong(), in.readUtf(64));
    }

    private static void handleQuery(Query message, Supplier<NetworkEvent.Context> source) {
        NetworkEvent.Context context = source.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> TlmPackServer.onQuery(sender, message.modelId()));
        context.setPacketHandled(true);
    }

    private static void handleInfo(Info message, Supplier<NetworkEvent.Context> source) {
        NetworkEvent.Context context = source.get();
        Connection connection = context.getNetworkManager();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TlmPackClient.onInfo(message, connection)));
        context.setPacketHandled(true);
    }

    private static void handleDownload(Download message, Supplier<NetworkEvent.Context> source) {
        NetworkEvent.Context context = source.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> TlmPackServer.onDownload(sender, message.modelId(), message.sha256()));
        context.setPacketHandled(true);
    }

    private static void handleChunk(Chunk message, Supplier<NetworkEvent.Context> source) {
        NetworkEvent.Context context = source.get();
        Connection connection = context.getNetworkManager();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TlmPackClient.onChunk(message, connection)));
        context.setPacketHandled(true);
    }
}
