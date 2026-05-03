package com.earth2me.essentials.redis;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.ess3.provider.SchedulingProvider;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class CrossServerTeleportManager implements Listener, AutoCloseable {
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String PLAYERS_KEY = "players";
    private static final String PLAYER_NAMESPACE = "player";
    private static final String PENDING_NAMESPACE = "pending-tpo";
    private static final String TPO_CHANNEL_NAMESPACE = "tpo";
    private static final String TYPE_LOCAL = "LOCAL";
    private static final String TYPE_TRANSFER = "TRANSFER";
    private static final String MODE_SELF_TO_PLAYER = "SELF_TO_PLAYER";
    private static final String MODE_PLAYER_TO_PLAYER = "PLAYER_TO_PLAYER";
    private static final long PLAYER_TTL_SECONDS = 30;
    private static final long PENDING_TTL_SECONDS = 60;

    private final Essentials plugin;
    private final RedisManager redis;
    private final String serverId;
    private final ConcurrentMap<UUID, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SchedulingProvider.EssentialsTask> heartbeatTasks = new ConcurrentHashMap<>();
    private SchedulingProvider.EssentialsTask refreshTask;

    public CrossServerTeleportManager(final Essentials plugin, final RedisManager redis) {
        this.plugin = plugin;
        this.redis = redis;
        this.serverId = redis.getServerId();
    }

    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        redis.subscribe(redis.channel(TPO_CHANNEL_NAMESPACE + ":" + serverId), this::handleMessage);
        refreshTask = plugin.runTaskTimerAsynchronously(this::refreshRemotePlayers, 20, 100);
        plugin.scheduleGlobalDelayedTask(() -> {
            for (final Player player : plugin.getOnlinePlayers()) {
                startHeartbeat(player);
            }
        });
    }

    public boolean teleportSelfToRemotePlayer(final User user, final RemotePlayer destination) {
        if (!isRemote(destination)) {
            return false;
        }

        storePendingTeleport(user.getUUID(), destination.getUuid(), user.getDisplayName(), MODE_SELF_TO_PLAYER)
                .thenRun(() -> plugin.scheduleEntityDelayedTask(user.getBase(), () -> transferPlayer(user.getBase(), destination.getServerId())))
                .exceptionally(ex -> {
                    logRedisFailure("store cross-server /tpo request", ex);
                    plugin.scheduleEntityDelayedTask(user.getBase(), () -> user.sendTl("playerNotFound"));
                    return null;
                });
        user.sendTl("teleporting");
        return true;
    }

    public boolean teleportLocalPlayerToRemotePlayer(final User initiator, final User teleportee, final RemotePlayer destination) {
        if (!isRemote(destination)) {
            return false;
        }

        storePendingTeleport(teleportee.getUUID(), destination.getUuid(), initiator.getDisplayName(), MODE_PLAYER_TO_PLAYER)
                .thenRun(() -> plugin.scheduleEntityDelayedTask(teleportee.getBase(), () -> transferPlayer(teleportee.getBase(), destination.getServerId())))
                .exceptionally(ex -> {
                    logRedisFailure("store cross-server /tpo request", ex);
                    plugin.scheduleEntityDelayedTask(initiator.getBase(), () -> initiator.sendTl("playerNotFound"));
                    return null;
                });
        initiator.sendTl("teleporting");
        return true;
    }

    public boolean teleportRemotePlayerToLocalPlayer(final User initiator, final RemotePlayer teleportee, final User destination) {
        if (!isRemote(teleportee)) {
            return false;
        }

        storePendingTeleport(teleportee.getUuid(), destination.getUUID(), initiator.getDisplayName(), MODE_PLAYER_TO_PLAYER)
                .thenRun(() -> publishTransfer(teleportee, serverId, destination.getUUID(), initiator.getDisplayName()))
                .exceptionally(ex -> {
                    logRedisFailure("store cross-server /tpo request", ex);
                    plugin.scheduleEntityDelayedTask(initiator.getBase(), () -> initiator.sendTl("playerNotFound"));
                    return null;
                });
        initiator.sendTl("teleporting");
        return true;
    }

    public boolean teleportRemotePlayerToRemotePlayer(final User initiator, final RemotePlayer teleportee, final RemotePlayer destination) {
        if (!isRemote(teleportee) || !isRemote(destination)) {
            return false;
        }

        if (teleportee.getServerId().equals(destination.getServerId())) {
            publishLocalTeleport(teleportee, destination.getUuid(), initiator.getDisplayName(), MODE_PLAYER_TO_PLAYER);
            initiator.sendTl("teleporting");
            return true;
        }

        storePendingTeleport(teleportee.getUuid(), destination.getUuid(), initiator.getDisplayName(), MODE_PLAYER_TO_PLAYER)
                .thenRun(() -> publishTransfer(teleportee, destination.getServerId(), destination.getUuid(), initiator.getDisplayName()))
                .exceptionally(ex -> {
                    logRedisFailure("store cross-server /tpo request", ex);
                    plugin.scheduleEntityDelayedTask(initiator.getBase(), () -> initiator.sendTl("playerNotFound"));
                    return null;
                });
        initiator.sendTl("teleporting");
        return true;
    }

    public RemotePlayer findPlayer(final String searchTerm, final boolean includeHidden) {
        final String lowerSearch = searchTerm.toLowerCase(Locale.ENGLISH);
        RemotePlayer prefixMatch = null;
        for (final RemotePlayer player : remotePlayers.values()) {
            if (!includeHidden && player.isHidden()) {
                continue;
            }
            final String name = player.getName().toLowerCase(Locale.ENGLISH);
            if (name.equals(lowerSearch)) {
                return player;
            }
            if (name.startsWith(lowerSearch)) {
                if (prefixMatch != null) {
                    return null;
                }
                prefixMatch = player;
            }
        }
        return prefixMatch;
    }

    public List<String> getPlayerNames(final boolean includeHidden) {
        final List<String> names = new ArrayList<>();
        for (final RemotePlayer player : remotePlayers.values()) {
            if (includeHidden || !player.isHidden()) {
                names.add(player.getName());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        startHeartbeat(event.getPlayer());
        handlePendingTeleport(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        final SchedulingProvider.EssentialsTask task = heartbeatTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        unregisterPlayer(playerId);
    }

    private void startHeartbeat(final Player player) {
        final SchedulingProvider.EssentialsTask oldTask = heartbeatTasks.remove(player.getUniqueId());
        if (oldTask != null) {
            oldTask.cancel();
        }
        final SchedulingProvider.EssentialsTask task = plugin.scheduleEntityRepeatingTask(player, () -> registerPlayer(player), 1, 200);
        heartbeatTasks.put(player.getUniqueId(), task);
    }

    private void registerPlayer(final Player player) {
        if (!player.isOnline()) {
            return;
        }
        final User user = plugin.getUser(player);
        final Map<String, String> values = new HashMap<>();
        values.put("uuid", player.getUniqueId().toString());
        values.put("name", player.getName());
        values.put("displayName", user.getDisplayName());
        values.put("server", serverId);
        values.put("hidden", Boolean.toString(user.isHidden()));
        values.put("lastSeen", Long.toString(System.currentTimeMillis()));

        final String playerKey = redis.key(PLAYER_NAMESPACE, player.getUniqueId().toString());
        redis.async().hset(playerKey, values);
        redis.async().expire(playerKey, PLAYER_TTL_SECONDS);
        redis.async().sadd(redis.key(PLAYERS_KEY), player.getUniqueId().toString());
    }

    private void unregisterPlayer(final UUID playerId) {
        redis.async().del(redis.key(PLAYER_NAMESPACE, playerId.toString()));
        redis.async().srem(redis.key(PLAYERS_KEY), playerId.toString());
    }

    private void refreshRemotePlayers() {
        redis.async().smembers(redis.key(PLAYERS_KEY)).toCompletableFuture()
                .thenCompose(this::loadRemotePlayers)
                .exceptionally(ex -> {
                    logRedisFailure("refresh remote player cache", ex);
                    return null;
                });
    }

    private CompletableFuture<Void> loadRemotePlayers(final Collection<String> playerIds) {
        if (playerIds.isEmpty()) {
            remotePlayers.clear();
            return CompletableFuture.completedFuture(null);
        }

        final List<CompletableFuture<RemotePlayer>> futures = new ArrayList<>();
        for (final String playerId : playerIds) {
            futures.add(redis.async().hgetall(redis.key(PLAYER_NAMESPACE, playerId)).toCompletableFuture()
                    .thenApply(values -> parseRemotePlayer(playerId, values))
                    .exceptionally(ex -> {
                        logRedisFailure("load remote player " + playerId, ex);
                        return null;
                    }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
            final Map<UUID, RemotePlayer> updated = new HashMap<>();
            for (final CompletableFuture<RemotePlayer> future : futures) {
                final RemotePlayer player = future.join();
                if (player != null && isRemote(player)) {
                    updated.put(player.getUuid(), player);
                }
            }
            remotePlayers.clear();
            remotePlayers.putAll(updated);
        });
    }

    private RemotePlayer parseRemotePlayer(final String playerId, final Map<String, String> values) {
        if (values.isEmpty()) {
            redis.async().srem(redis.key(PLAYERS_KEY), playerId);
            return null;
        }

        try {
            final UUID uuid = UUID.fromString(values.get("uuid"));
            final String name = values.get("name");
            final String playerServer = values.get("server");
            if (isBlank(name) || isBlank(playerServer)) {
                return null;
            }
            return new RemotePlayer(uuid, name, values.get("displayName"), playerServer, Boolean.parseBoolean(values.get("hidden")));
        } catch (final IllegalArgumentException ex) {
            redis.async().srem(redis.key(PLAYERS_KEY), playerId);
            return null;
        }
    }

    private CompletableFuture<Void> storePendingTeleport(final UUID teleportee, final UUID destination, final String initiatorName, final String mode) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("destination", destination.toString());
        values.put("initiator", initiatorName);
        values.put("mode", mode);
        final String key = redis.key(PENDING_NAMESPACE, teleportee.toString());
        final CompletableFuture<?> write = redis.async().hset(key, values).toCompletableFuture();
        final CompletableFuture<?> expire = redis.async().expire(key, PENDING_TTL_SECONDS).toCompletableFuture();
        return CompletableFuture.allOf(write, expire);
    }

    private void handlePendingTeleport(final Player player) {
        final String key = redis.key(PENDING_NAMESPACE, player.getUniqueId().toString());
        redis.async().hgetall(key).toCompletableFuture()
                .thenAccept(values -> {
                    if (values.isEmpty()) {
                        return;
                    }
                    redis.async().del(key);
                    plugin.scheduleEntityDelayedTask(player, () -> completePendingTeleport(player, values));
                })
                .exceptionally(ex -> {
                    logRedisFailure("load pending cross-server /tpo request", ex);
                    return null;
                });
    }

    private void completePendingTeleport(final Player player, final Map<String, String> values) {
        final Player destination = getPlayer(values.get("destination"));
        if (destination == null) {
            return;
        }
        teleportPlayerToLocalTarget(player, destination, values.get("initiator"), values.get("mode"));
    }

    private void handleMessage(final String rawMessage) {
        final CrossServerMessage message = CrossServerMessage.decode(rawMessage);
        if (message == null) {
            return;
        }

        if (TYPE_LOCAL.equals(message.getType())) {
            plugin.scheduleGlobalDelayedTask(() -> {
                final Player teleportee = getPlayer(message.getTeleportee());
                final Player destination = getPlayer(message.getDestination());
                if (teleportee == null || destination == null) {
                    return;
                }
                plugin.scheduleEntityDelayedTask(teleportee, () -> teleportPlayerToLocalTarget(teleportee, destination, message.getInitiatorName(), message.getMode()));
            });
            return;
        }

        if (TYPE_TRANSFER.equals(message.getType())) {
            plugin.scheduleGlobalDelayedTask(() -> {
                final Player teleportee = getPlayer(message.getTeleportee());
                if (teleportee != null) {
                    plugin.scheduleEntityDelayedTask(teleportee, () -> transferPlayer(teleportee, message.getDestinationServer()));
                }
            });
        }
    }

    private void publishLocalTeleport(final RemotePlayer teleportee, final UUID destination, final String initiatorName, final String mode) {
        final CrossServerMessage message = CrossServerMessage.local(teleportee.getUuid(), destination, initiatorName, mode);
        publish(teleportee.getServerId(), message);
    }

    private void publishTransfer(final RemotePlayer teleportee, final String destinationServer, final UUID destination, final String initiatorName) {
        final CrossServerMessage message = CrossServerMessage.transfer(teleportee.getUuid(), destinationServer, destination, initiatorName);
        publish(teleportee.getServerId(), message);
    }

    private void publish(final String destinationServer, final CrossServerMessage message) {
        redis.async().publish(redis.channel(TPO_CHANNEL_NAMESPACE + ":" + destinationServer), message.encode()).toCompletableFuture()
                .exceptionally(ex -> {
                    logRedisFailure("publish cross-server /tpo request", ex);
                    return null;
                });
    }

    private void teleportPlayerToLocalTarget(final Player teleportee, final Player destination, final String initiatorName, final String mode) {
        if (!teleportee.isOnline() || !destination.isOnline()) {
            return;
        }

        plugin.scheduleEntityDelayedTask(destination, () -> {
            if (!destination.isOnline()) {
                return;
            }

            final Location destinationLocation = destination.getLocation();
            final String destinationDisplayName = destination.getDisplayName();
            final String worldName = destinationLocation.getWorld().getName();
            final int blockX = destinationLocation.getBlockX();
            final int blockY = destinationLocation.getBlockY();
            final int blockZ = destinationLocation.getBlockZ();

            plugin.scheduleEntityDelayedTask(teleportee, () -> {
                if (!teleportee.isOnline()) {
                    return;
                }

                final User teleporteeUser = plugin.getUser(teleportee);
                final CompletableFuture<Boolean> future = new CompletableFuture<>();
                teleporteeUser.getAsyncTeleport().nowUnsafe(destinationLocation, TeleportCause.COMMAND, future);
                future.thenAccept(success -> {
                    if (!success) {
                        return;
                    }
                    if (MODE_PLAYER_TO_PLAYER.equals(mode)) {
                        teleporteeUser.sendTl("teleportAtoB", initiatorName, destinationDisplayName);
                    } else {
                        teleporteeUser.sendTl("teleporting", worldName, blockX, blockY, blockZ);
                    }
                }).exceptionally(ex -> {
                    logRedisFailure("complete cross-server /tpo request", ex);
                    return null;
                });
            });
        });
    }

    private void transferPlayer(final Player player, final String destinationServer) {
        if (isBlank(destinationServer) || !player.isOnline()) {
            return;
        }

        try {
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(byteStream);
            out.writeUTF("Connect");
            out.writeUTF(destinationServer);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, byteStream.toByteArray());
        } catch (final IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to send proxy transfer for cross-server /tpo", ex);
        }
    }

    private Player getPlayer(final String uuid) {
        try {
            return plugin.getServer().getPlayer(UUID.fromString(uuid));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private Player getPlayer(final UUID uuid) {
        return plugin.getServer().getPlayer(uuid);
    }

    private boolean isRemote(final RemotePlayer player) {
        return player != null && !serverId.equals(player.getServerId());
    }

    private void logRedisFailure(final String action, final Throwable throwable) {
        final Throwable unwrapped = unwrap(throwable);
        plugin.getLogger().warning("Failed to " + action + ": " + unwrapped.getMessage());
    }

    private Throwable unwrap(final Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        for (final SchedulingProvider.EssentialsTask task : heartbeatTasks.values()) {
            task.cancel();
        }
        heartbeatTasks.clear();
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        try {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        } catch (final IllegalArgumentException ignored) {
        }
    }

    public static final class RemotePlayer {
        private final UUID uuid;
        private final String name;
        private final String displayName;
        private final String serverId;
        private final boolean hidden;

        private RemotePlayer(final UUID uuid, final String name, final String displayName, final String serverId, final boolean hidden) {
            this.uuid = uuid;
            this.name = name;
            this.displayName = displayName;
            this.serverId = serverId;
            this.hidden = hidden;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return isBlank(displayName) ? name : displayName;
        }

        public String getServerId() {
            return serverId;
        }

        public boolean isHidden() {
            return hidden;
        }
    }

    private static final class CrossServerMessage {
        private final String type;
        private final UUID teleportee;
        private final String destinationServer;
        private final UUID destination;
        private final String initiatorName;
        private final String mode;

        private CrossServerMessage(final String type, final UUID teleportee, final String destinationServer, final UUID destination, final String initiatorName, final String mode) {
            this.type = type;
            this.teleportee = teleportee;
            this.destinationServer = destinationServer;
            this.destination = destination;
            this.initiatorName = initiatorName;
            this.mode = mode;
        }

        private static CrossServerMessage local(final UUID teleportee, final UUID destination, final String initiatorName, final String mode) {
            return new CrossServerMessage(TYPE_LOCAL, teleportee, "", destination, initiatorName, mode);
        }

        private static CrossServerMessage transfer(final UUID teleportee, final String destinationServer, final UUID destination, final String initiatorName) {
            return new CrossServerMessage(TYPE_TRANSFER, teleportee, destinationServer, destination, initiatorName, MODE_PLAYER_TO_PLAYER);
        }

        private String encode() {
            return type + "\t" + teleportee + "\t" + encodeField(destinationServer) + "\t" + destination + "\t" + encodeField(initiatorName) + "\t" + mode;
        }

        private static CrossServerMessage decode(final String raw) {
            final String[] parts = raw.split("\t", -1);
            if (parts.length != 6) {
                return null;
            }

            try {
                return new CrossServerMessage(
                        parts[0],
                        UUID.fromString(parts[1]),
                        decodeField(parts[2]),
                        UUID.fromString(parts[3]),
                        decodeField(parts[4]),
                        parts[5]);
            } catch (final IllegalArgumentException ex) {
                return null;
            }
        }

        private String getType() {
            return type;
        }

        private UUID getTeleportee() {
            return teleportee;
        }

        private String getDestinationServer() {
            return destinationServer;
        }

        private UUID getDestination() {
            return destination;
        }

        private String getInitiatorName() {
            return initiatorName;
        }

        private String getMode() {
            return mode;
        }

        private static String encodeField(final String value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        }

        private static String decodeField(final String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }
}
