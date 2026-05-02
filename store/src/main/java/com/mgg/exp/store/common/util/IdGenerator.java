package com.mgg.exp.store.common.util;

import java.util.concurrent.atomic.AtomicLong;

public final class IdGenerator {

    private static final long EPOCH = 1704067200000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID = 1L;
    private static final long DATACENTER_ID = 1L;

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static volatile long LAST_TIMESTAMP = -1L;

    private IdGenerator() {
    }

    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < LAST_TIMESTAMP) {
            throw new RuntimeException("Clock moved backwards");
        }
        if (timestamp == LAST_TIMESTAMP) {
            long seq = SEQUENCE.incrementAndGet() & SEQUENCE_MASK;
            if (seq == 0) {
                timestamp = waitNextMillis(LAST_TIMESTAMP);
            }
        } else {
            SEQUENCE.set(0);
        }
        LAST_TIMESTAMP = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (DATACENTER_ID << DATACENTER_ID_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | (SEQUENCE.get() & SEQUENCE_MASK);
    }

    public static String nextIdStr() {
        return String.valueOf(nextId());
    }

    private static long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
