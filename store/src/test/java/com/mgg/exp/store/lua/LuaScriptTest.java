package com.mgg.exp.store.lua;

import com.mgg.exp.store.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuaScriptTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("2.1 deduct.lua 扣减脚本")
    class DeductLuaTest {

        private final DefaultRedisScript<Long> deductScript = new DefaultRedisScript<>() {{
            setScriptText("""
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                local total = tonumber(redis.call('GET', KEYS[2]) or '0')
                local quantity = tonumber(ARGV[1])
                if current >= quantity and total >= quantity then
                    redis.call('DECRBY', KEYS[1], quantity)
                    local remaining = redis.call('DECRBY', KEYS[2], quantity)
                    if tonumber(remaining) <= 0 then
                        return 2
                    end
                    return 1
                else
                    return 0
                end
                """);
            setResultType(Long.class);
        }};

        @Test
        @DisplayName("LUA-FUNC-001: 正常扣减-桶余量和total_remaining充足")
        void testNormalDeduct() {
            redisTemplate.opsForValue().set("test:bucket:0", "100");
            redisTemplate.opsForValue().set("test:total_remaining", "1000");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "10");

            assertEquals(1L, result);
            assertEquals("90", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("990", redisTemplate.opsForValue().get("test:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-002: 扣减成功且分桶耗尽-返回2")
        void testDeductBucketExhausted() {
            redisTemplate.opsForValue().set("test:bucket:0", "10");
            redisTemplate.opsForValue().set("test:total_remaining", "10");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "10");

            assertEquals(2L, result);
            assertEquals("0", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("0", redisTemplate.opsForValue().get("test:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-003: 桶余量不足-返回0")
        void testBucketInsufficient() {
            redisTemplate.opsForValue().set("test:bucket:0", "5");
            redisTemplate.opsForValue().set("test:total_remaining", "1000");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "10");

            assertEquals(0L, result);
            assertEquals("5", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("1000", redisTemplate.opsForValue().get("test:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-004: total_remaining不足-返回0")
        void testTotalRemainingInsufficient() {
            redisTemplate.opsForValue().set("test:bucket:0", "100");
            redisTemplate.opsForValue().set("test:total_remaining", "5");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "10");

            assertEquals(0L, result);
            assertEquals("100", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("5", redisTemplate.opsForValue().get("test:total_remaining"));
        }

        @Test
        @DisplayName("LUA-EXCP-001: KEY不存在-桶和total_remaining均不存在")
        void testKeyNotExist() {
            Long result = redisTemplate.execute(deductScript,
                    List.of("test:nonexist:bucket:0", "test:nonexist:total_remaining"), "10");

            assertEquals(0L, result);
        }

        @Test
        @DisplayName("LUA-FUNC-005: 扣减数量为1-最小单位扣减")
        void testMinDeduct() {
            redisTemplate.opsForValue().set("test:bucket:0", "1");
            redisTemplate.opsForValue().set("test:total_remaining", "1");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "1");

            assertEquals(2L, result);
            assertEquals("0", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("0", redisTemplate.opsForValue().get("test:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-006: 扣减后total_remaining为正数-返回1")
        void testDeductRemainingPositive() {
            redisTemplate.opsForValue().set("test:bucket:0", "100");
            redisTemplate.opsForValue().set("test:total_remaining", "20");

            Long result = redisTemplate.execute(deductScript,
                    List.of("test:bucket:0", "test:total_remaining"), "10");

            assertEquals(1L, result);
            assertEquals("90", redisTemplate.opsForValue().get("test:bucket:0"));
            assertEquals("10", redisTemplate.opsForValue().get("test:total_remaining"));
        }
    }

    @Nested
    @DisplayName("2.2 init_buckets.lua 初始化脚本")
    class InitBucketsLuaTest {

        private final DefaultRedisScript<Long> initScript = new DefaultRedisScript<>() {{
            setScriptText("""
                local bucketCount = #KEYS - 2
                local metaKey = KEYS[bucketCount + 1]
                local totalRemainingKey = KEYS[bucketCount + 2]
                local quantityPerBucket = tonumber(ARGV[1])
                local metaValue = ARGV[2]
                
                for i = 1, bucketCount do
                    redis.call('SET', KEYS[i], quantityPerBucket)
                end
                redis.call('SET', metaKey, metaValue)
                redis.call('SET', totalRemainingKey, bucketCount * quantityPerBucket)
                return bucketCount
                """);
            setResultType(Long.class);
        }};

        @Test
        @DisplayName("LUA-FUNC-007: 正常初始化16个分桶+meta+total_remaining")
        void testNormalInit() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                keys.add("test:init:bucket:" + i);
            }
            keys.add("test:init:meta");
            keys.add("test:init:total_remaining");

            Long result = redisTemplate.execute(initScript, keys, "625", "10001:16");

            assertEquals(16L, result);
            for (int i = 0; i < 16; i++) {
                assertEquals("625", redisTemplate.opsForValue().get("test:init:bucket:" + i));
            }
            assertEquals("10001:16", redisTemplate.opsForValue().get("test:init:meta"));
            assertEquals("10000", redisTemplate.opsForValue().get("test:init:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-008: 原子性验证-初始化全部成功")
        void testAtomicInit() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                keys.add("test:atomic:bucket:" + i);
            }
            keys.add("test:atomic:meta");
            keys.add("test:atomic:total_remaining");

            Long result = redisTemplate.execute(initScript, keys, "100", "10001:4");

            assertEquals(4L, result);
            for (int i = 0; i < 4; i++) {
                assertEquals("100", redisTemplate.opsForValue().get("test:atomic:bucket:" + i));
            }
        }

        @Test
        @DisplayName("LUA-EXCP-002: 重复初始化-覆盖已有值")
        void testOverwriteInit() {
            redisTemplate.opsForValue().set("test:overwrite:bucket:0", "100");

            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                keys.add("test:overwrite:bucket:" + i);
            }
            keys.add("test:overwrite:meta");
            keys.add("test:overwrite:total_remaining");

            redisTemplate.execute(initScript, keys, "50", "10001:2");

            assertEquals("50", redisTemplate.opsForValue().get("test:overwrite:bucket:0"));
        }
    }

    @Nested
    @DisplayName("2.3 cleanup_buckets.lua 清理脚本")
    class CleanupBucketsLuaTest {

        private final DefaultRedisScript<Long> cleanupScript = new DefaultRedisScript<>() {{
            setScriptText("""
                local bucketCount = #KEYS - 2
                local metaKey = KEYS[bucketCount + 1]
                local totalRemainingKey = KEYS[bucketCount + 2]
                
                for i = 1, bucketCount do
                    redis.call('DEL', KEYS[i])
                end
                redis.call('DEL', metaKey)
                redis.call('DEL', totalRemainingKey)
                return 1
                """);
            setResultType(Long.class);
        }};

        @Test
        @DisplayName("LUA-FUNC-009: 正常清理所有分桶+meta+total_remaining")
        void testNormalCleanup() {
            for (int i = 0; i < 4; i++) {
                redisTemplate.opsForValue().set("test:clean:bucket:" + i, "100");
            }
            redisTemplate.opsForValue().set("test:clean:meta", "10001:4");
            redisTemplate.opsForValue().set("test:clean:total_remaining", "400");

            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                keys.add("test:clean:bucket:" + i);
            }
            keys.add("test:clean:meta");
            keys.add("test:clean:total_remaining");

            Long result = redisTemplate.execute(cleanupScript, keys);

            assertEquals(1L, result);
            for (int i = 0; i < 4; i++) {
                assertNull(redisTemplate.opsForValue().get("test:clean:bucket:" + i));
            }
            assertNull(redisTemplate.opsForValue().get("test:clean:meta"));
            assertNull(redisTemplate.opsForValue().get("test:clean:total_remaining"));
        }

        @Test
        @DisplayName("LUA-EXCP-003: 重复清理幂等-Key已不存在")
        void testIdempotentCleanup() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                keys.add("test:idem:bucket:" + i);
            }
            keys.add("test:idem:meta");
            keys.add("test:idem:total_remaining");

            Long result = redisTemplate.execute(cleanupScript, keys);

            assertEquals(1L, result);
        }
    }

    @Nested
    @DisplayName("2.4 incr_refund.lua INCR回补脚本")
    class IncrRefundLuaTest {

        private final DefaultRedisScript<Long> incrRefundScript = new DefaultRedisScript<>() {{
            setScriptText("""
                local metaExists = redis.call('EXISTS', KEYS[1])
                if tonumber(metaExists) == 1 then
                    redis.call('INCRBY', KEYS[2], ARGV[1])
                    redis.call('INCRBY', KEYS[3], ARGV[1])
                    return 1
                else
                    return 0
                end
                """);
            setResultType(Long.class);
        }};

        @Test
        @DisplayName("LUA-FUNC-010: meta有效时INCR回补成功")
        void testIncrRefundMetaValid() {
            redisTemplate.opsForValue().set("test:refund:meta", "10001:16");
            redisTemplate.opsForValue().set("test:refund:bucket:3", "50");
            redisTemplate.opsForValue().set("test:refund:total_remaining", "500");

            Long result = redisTemplate.execute(incrRefundScript,
                    List.of("test:refund:meta", "test:refund:bucket:3", "test:refund:total_remaining"),
                    "10");

            assertEquals(1L, result);
            assertEquals("60", redisTemplate.opsForValue().get("test:refund:bucket:3"));
            assertEquals("510", redisTemplate.opsForValue().get("test:refund:total_remaining"));
        }

        @Test
        @DisplayName("LUA-FUNC-011: meta已失效时跳过INCR回补")
        void testIncrRefundMetaInvalid() {
            redisTemplate.opsForValue().set("test:refund2:bucket:3", "50");
            redisTemplate.opsForValue().set("test:refund2:total_remaining", "500");

            Long result = redisTemplate.execute(incrRefundScript,
                    List.of("test:refund2:meta", "test:refund2:bucket:3", "test:refund2:total_remaining"),
                    "10");

            assertEquals(0L, result);
            assertEquals("50", redisTemplate.opsForValue().get("test:refund2:bucket:3"));
            assertEquals("500", redisTemplate.opsForValue().get("test:refund2:total_remaining"));
        }

        @Test
        @DisplayName("LUA-EXCP-004: bucket_index超出有效范围")
        void testInvalidBucketIndex() {
            redisTemplate.opsForValue().set("test:refund3:meta", "10001:16");

            Long result = redisTemplate.execute(incrRefundScript,
                    List.of("test:refund3:meta", "test:refund3:bucket:99", "test:refund3:total_remaining"),
                    "10");

            assertEquals(1L, result);
        }
    }
}
