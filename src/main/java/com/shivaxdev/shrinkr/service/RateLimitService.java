package com.shivaxdev.shrinkr.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
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

    private final ProxyManager<byte[]> proxyManager;

    public RateLimitService(LettuceConnectionFactory lettuceConnectionFactory) {

        Object nativeClient = lettuceConnectionFactory.getNativeClient();
        if (!(nativeClient instanceof RedisClient)) {
            throw new IllegalStateException(
                    "RateLimitService requires single-node Redis. Found: "
                    + nativeClient.getClass().getSimpleName());
        }
        RedisClient redisClient = (RedisClient) nativeClient;
        StatefulRedisConnection<byte[], byte[]> connection =
                redisClient.connect(ByteArrayCodec.INSTANCE);

        this.proxyManager = LettuceBasedProxyManager.builderFor(connection).build();
    }

    public boolean isAllowed(String ipAddress) {
        return tryConsume("rate:" + normalizeIp(ipAddress), requestsPerHour, Duration.ofHours(1));
    }

    public boolean isRedirectAllowed(String ipAddress) {
        return tryConsume("rate:redirect:" + normalizeIp(ipAddress), redirectsPerMinute, Duration.ofMinutes(1));
    }

    private boolean tryConsume(String redisKey, int capacity, Duration refillPeriod) {
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, refillPeriod)
                        .build())
                .build();

        var bucket = proxyManager.builder().build(redisKey.getBytes(), configSupplier);

        try {
            return bucket.tryConsume(1);
        } catch (Exception e) {

            log.warn("Rate limiter unavailable — failing open. reason={}", e.getMessage());
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
