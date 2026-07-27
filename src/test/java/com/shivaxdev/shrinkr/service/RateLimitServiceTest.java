package com.shivaxdev.shrinkr.service;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock LettuceConnectionFactory connectionFactory;
    @Mock RedisConnection redisConnection;
    @Mock StatefulRedisConnection<byte[], byte[]> statefulRedisConnection;
    @Mock RedisCommands<byte[], byte[]> syncCommands;
    @Mock RedisAsyncCommands<byte[], byte[]> asyncCommands;
    @Mock ProxyManager<byte[]> proxyManager;

    @Test
    void constructor_withStatefulRedisConnection_initializesProxyManager() {
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.getNativeConnection()).thenReturn(statefulRedisConnection);

        RateLimitService service = new RateLimitService(connectionFactory);
        assertThat(service).isNotNull();
    }

    @Test
    void constructor_withSyncRedisCommands_initializesProxyManager() {
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.getNativeConnection()).thenReturn(syncCommands);
        when(syncCommands.getStatefulConnection()).thenReturn(statefulRedisConnection);

        RateLimitService service = new RateLimitService(connectionFactory);
        assertThat(service).isNotNull();
    }

    @Test
    void constructor_withAsyncRedisCommands_initializesProxyManager() {
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.getNativeConnection()).thenReturn(asyncCommands);
        when(asyncCommands.getStatefulConnection()).thenReturn(statefulRedisConnection);

        RateLimitService service = new RateLimitService(connectionFactory);
        assertThat(service).isNotNull();
    }

    @Test
    void constructor_withUnknownConnection_failsOpenGracefully() {
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.getNativeConnection()).thenReturn("UnknownConnectionObject");

        RateLimitService service = new RateLimitService(connectionFactory);

        // When proxyManager is null, isAllowed and isRedirectAllowed should fail open (return true)
        assertThat(service.isAllowed("192.168.1.1")).isTrue();
        assertThat(service.isRedirectAllowed("192.168.1.1")).isTrue();
    }

    @Test
    void isAllowed_nullProxyManager_returnsTrue() {
        RateLimitService service = new RateLimitService(null, 50, 300);

        assertThat(service.isAllowed("10.0.0.1")).isTrue();
        assertThat(service.isRedirectAllowed("10.0.0.1")).isTrue();
    }

    @Test
    void isAllowed_ipv6Normalized_handlesSubnetPrefix() {
        RateLimitService service = new RateLimitService(null, 50, 300);

        // IPv6 normalization should not crash and should fail open when proxyManager is null
        assertThat(service.isAllowed("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).isTrue();
    }
}
