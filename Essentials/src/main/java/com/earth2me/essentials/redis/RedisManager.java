package com.earth2me.essentials.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.function.Consumer;

public final class RedisManager implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final String keyPrefix;
    private final String serverId;

    public RedisManager(final EssentialsRedisConfig config) {
        this.client = RedisClient.create(config.getUri());
        this.connection = client.connect();
        this.pubSubConnection = client.connectPubSub();
        this.keyPrefix = config.getKeyPrefix();
        this.serverId = config.getServerId();
    }

    public RedisAsyncCommands<String, String> async() {
        return connection.async();
    }

    public String key(final String namespace, final String id) {
        return keyPrefix + namespace + ":" + id;
    }

    public String key(final String namespace) {
        return keyPrefix + namespace;
    }

    public String channel(final String namespace) {
        return keyPrefix + "channel:" + namespace;
    }

    public String getServerId() {
        return serverId;
    }

    public void subscribe(final String channel, final Consumer<String> consumer) {
        pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(final String receivedChannel, final String message) {
                if (channel.equals(receivedChannel)) {
                    consumer.accept(message);
                }
            }
        });
        pubSubConnection.async().subscribe(channel);
    }

    @Override
    public void close() {
        try {
            pubSubConnection.close();
            connection.close();
        } finally {
            client.shutdown();
        }
    }
}
