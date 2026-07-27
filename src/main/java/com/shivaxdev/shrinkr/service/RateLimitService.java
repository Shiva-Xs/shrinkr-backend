package com.shivaxdev.shrinkr.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
public class RateLimitService {

    @Value("${app.rate-limit.requests-per-hour}")
    private int requestsPerHour;   

    @Value("${app.rate-limit.redirects-per-minute:300}")
    private int redirectsPerMinute;   

    private ProxyManager<byte[]> proxyManager;

    public RateLimitService(LettuceConnectionFactory lettuceConnectionFactory) {
        try {
            Object nativeConn = lettuceConnectionFactory.getConnection().getNativeConnection();
            this.proxyManager = initProxyManager(nativeConn);
            if (this.proxyManager != null) {
                log.info("RateLimitService: Successfully initialized Bucket4j Redis ProxyManager.");
            } else {
                log.warn("RateLimitService: Native connection type unsupported (found {}). Rate limiting disabled.",
                        nativeConn != null ? nativeConn.getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.warn("Failed to initialize Bucket4j Redis ProxyManager: {}. Rate limiting will fail open.", e.getMessage(), e);
            this.proxyManager = null;
        }
    }

    RateLimitService(ProxyManager<byte[]> proxyManager, int requestsPerHour, int redirectsPerMinute) {
        this.proxyManager = proxyManager;
        this.requestsPerHour = requestsPerHour;
        this.redirectsPerMinute = redirectsPerMinute;
    }

    private ProxyManager<byte[]> initProxyManager(Object nativeConn) {
        if (nativeConn == null) return null;

        if (nativeConn instanceof StatefulRedisConnection<?, ?> statefulConn) {
            @SuppressWarnings("unchecked")
            StatefulRedisConnection<byte[], byte[]> byteConn = (StatefulRedisConnection<byte[], byte[]>) statefulConn;
            return LettuceBasedProxyManager.builderFor(byteConn).build();
        }
        if (nativeConn instanceof io.lettuce.core.api.async.RedisAsyncCommands<?, ?> asyncCmds) {
            @SuppressWarnings("unchecked")
            StatefulRedisConnection<byte[], byte[]> byteConn = (StatefulRedisConnection<byte[], byte[]>) asyncCmds.getStatefulConnection();
            return LettuceBasedProxyManager.builderFor(byteConn).build();
        }
        if (nativeConn instanceof io.lettuce.core.api.sync.RedisCommands<?, ?> syncCmds) {
            @SuppressWarnings("unchecked")
            StatefulRedisConnection<byte[], byte[]> byteConn = (StatefulRedisConnection<byte[], byte[]>) syncCmds.getStatefulConnection();
            return LettuceBasedProxyManager.builderFor(byteConn).build();
        }
        if (nativeConn instanceof io.lettuce.core.cluster.api.StatefulRedisClusterConnection<?, ?> clusterConn) {
            @SuppressWarnings("unchecked")
            StatefulRedisClusterConnection<byte[], byte[]> byteClusterConn = (StatefulRedisClusterConnection<byte[], byte[]>) clusterConn;
            return LettuceBasedProxyManager.builderFor(byteClusterConn).build();
        }
        if (nativeConn instanceof io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands<?, ?> clusterAsyncCmds) {
            @SuppressWarnings("unchecked")
            StatefulRedisClusterConnection<byte[], byte[]> byteClusterConn = (StatefulRedisClusterConnection<byte[], byte[]>) clusterAsyncCmds.getStatefulConnection();
            return LettuceBasedProxyManager.builderFor(byteClusterConn).build();
        }
        if (nativeConn instanceof io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands<?, ?> clusterSyncCmds) {
            @SuppressWarnings("unchecked")
            StatefulRedisClusterConnection<byte[], byte[]> byteClusterConn = (StatefulRedisClusterConnection<byte[], byte[]>) clusterSyncCmds.getStatefulConnection();
            return LettuceBasedProxyManager.builderFor(byteClusterConn).build();
        }

        try {
            java.lang.reflect.Method getStatefulMethod = nativeConn.getClass().getMethod("getStatefulConnection");
            Object connObj = getStatefulMethod.invoke(nativeConn);
            if (connObj instanceof StatefulRedisConnection<?, ?> sConn) {
                @SuppressWarnings("unchecked")
                StatefulRedisConnection<byte[], byte[]> byteConn = (StatefulRedisConnection<byte[], byte[]>) sConn;
                return LettuceBasedProxyManager.builderFor(byteConn).build();
            } else if (connObj instanceof io.lettuce.core.cluster.api.StatefulRedisClusterConnection<?, ?> cConn) {
                @SuppressWarnings("unchecked")
                StatefulRedisClusterConnection<byte[], byte[]> byteClusterConn = (StatefulRedisClusterConnection<byte[], byte[]>) cConn;
                return LettuceBasedProxyManager.builderFor(byteClusterConn).build();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public boolean isAllowed(String ipAddress) {
        return tryConsume("rate:" + normalizeIp(ipAddress), requestsPerHour, Duration.ofHours(1));
    }

    public boolean isRedirectAllowed(String ipAddress) {
        return tryConsume("rate:redirect:" + normalizeIp(ipAddress), redirectsPerMinute, Duration.ofMinutes(1));
    }

    private boolean tryConsume(String redisKey, int capacity, Duration refillPeriod) {
        if (proxyManager == null) {
            log.warn("Rate limit check bypassed: proxyManager is null. Key={}", redisKey);
            return true;
        }
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, refillPeriod)
                        .build())
                .build();

        try {
            var bucket = proxyManager.builder().build(redisKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), configSupplier);
            return bucket.tryConsume(1);
        } catch (Exception e) {
            log.warn("Rate limiter unavailable — failing open. key={} reason={}", redisKey, e.getMessage());
            return true;
        }
    }

    private String normalizeIp(String ip) {
        if (ip == null) return "unknown";

        if (ip.contains(":")) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                String expanded = addr.getHostAddress();

                String[] parts = expanded.split(":");
                if (parts.length >= 4) {
                    return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
                }
            } catch (java.net.UnknownHostException e) {
                return ip;
            }
        }
        return ip;
    }
}
