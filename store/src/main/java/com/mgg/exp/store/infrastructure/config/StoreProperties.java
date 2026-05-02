package com.mgg.exp.store.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "store")
public class StoreProperties {

    private Bucket bucket = new Bucket();
    private Merge merge = new Merge();
    private AutoLock autoLock = new AutoLock();
    private Redis redis = new Redis();
    private Lock lock = new Lock();

    @Data
    public static class Bucket {
        private int count = 16;
        private int falloverMaxRetries = 3;
    }

    @Data
    public static class Merge {
        private long delayMs = 1000;
        private int idleQpsThreshold = 100;
    }

    @Data
    public static class AutoLock {
        private boolean enabled = true;
        private double reserveRatio = 0.1;
        private int minLockQuantity = 100;
        private int maxActive = 2;
        private double triggerRatio = 0.3;
        private long checkIntervalMs = 500;
        private long expireSeconds = 300;
    }

    @Data
    public static class Redis {
        private int failThreshold = 5;
    }

    @Data
    public static class Lock {
        private long waitTimeSeconds = 10;
        private long leaseTimeSeconds = 30;
    }
}
