package com.mgg.exp.store;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import jakarta.annotation.PreDestroy;
import redis.embedded.RedisServer;

@TestConfiguration
public class TestContainerConfig {

    private static final int REDIS_PORT = 6380;
    private static RedisServer redisServer;

    static {
        redisServer = RedisServer.builder()
                .port(REDIS_PORT)
                .setting("maxmemory 128mb")
                .build();
        redisServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (redisServer.isActive()) {
                redisServer.stop();
            }
        }));
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory("127.0.0.1", REDIS_PORT);
    }

    @Bean(destroyMethod = "shutdown")
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new StringCodec());
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + REDIS_PORT);
        return Redisson.create(config);
    }

    @PreDestroy
    public void tearDown() {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
        }
    }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (redisServer == null || !redisServer.isActive()) {
                redisServer = RedisServer.builder()
                        .port(REDIS_PORT)
                        .setting("maxmemory 128mb")
                        .build();
                redisServer.start();
            }
        }
    }
}
