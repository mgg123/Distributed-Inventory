package com.mgg.exp.store.infrastructure.gateway;

import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisBucketGatewayImpl implements RedisBucketGateway {

    private final RedissonClient redissonClient;
    private final StoreProperties storeProperties;

    private static final String BUCKET_KEY_PATTERN = "inventory:{%s}:lock:bucket:%d";
    private static final String META_KEY_PATTERN = "inventory:{%s}:lock:meta";
    private static final String TOTAL_REMAINING_KEY_PATTERN = "inventory:{%s}:lock:total_remaining";

    private String bucketKey(String lockOrderId, int index) {
        return String.format(BUCKET_KEY_PATTERN, lockOrderId, index);
    }

    private String metaKey(String lockOrderId) {
        return String.format(META_KEY_PATTERN, lockOrderId);
    }

    private String totalRemainingKey(String lockOrderId) {
        return String.format(TOTAL_REMAINING_KEY_PATTERN, lockOrderId);
    }

    @Override
    public boolean initBuckets(String lockOrderId, Long skuId, int bucketCount,
                                int quantityPerBucket) {
        try {
            List<Object> keys = new ArrayList<>();
            for (int i = 0; i < bucketCount; i++) {
                keys.add(bucketKey(lockOrderId, i));
            }
            keys.add(metaKey(lockOrderId));
            keys.add(totalRemainingKey(lockOrderId));

            String metaValue = skuId + ":" + bucketCount;
            String script = loadScript("lua/init_buckets.lua");

            RScript rScript = redissonClient.getScript();
            rScript.eval(RScript.Mode.READ_WRITE, script,
                    RScript.ReturnType.INTEGER, keys, quantityPerBucket, metaValue);
            return true;
        } catch (Exception e) {
            log.error("initBuckets failed, lockOrderId: {}", lockOrderId, e);
            return false;
        }
    }

    @Override
    public int deduct(String lockOrderId, int bucketIndex, int quantity) {
        try {
            String script = loadScript("lua/deduct.lua");
            RScript rScript = redissonClient.getScript();
            List<Object> keys = List.of(
                    bucketKey(lockOrderId, bucketIndex),
                    totalRemainingKey(lockOrderId)
            );
            return rScript.eval(RScript.Mode.READ_WRITE, script,
                    RScript.ReturnType.INTEGER, keys, quantity);
        } catch (Exception e) {
            log.error("deduct failed, lockOrderId: {}, bucketIndex: {}", lockOrderId, bucketIndex, e);
            return -1;
        }
    }

    @Override
    public int incrRefund(String lockOrderId, int bucketIndex, int quantity) {
        try {
            if (bucketIndex < 0 || bucketIndex >= storeProperties.getBucket().getCount()) {
                log.error("invalid bucket_index: {}, bucketCount: {}",
                        bucketIndex, storeProperties.getBucket().getCount());
                return 0;
            }
            String script = loadScript("lua/incr_refund.lua");
            RScript rScript = redissonClient.getScript();
            List<Object> keys = List.of(
                    metaKey(lockOrderId),
                    bucketKey(lockOrderId, bucketIndex),
                    totalRemainingKey(lockOrderId)
            );
            return rScript.eval(RScript.Mode.READ_WRITE, script,
                    RScript.ReturnType.INTEGER, keys, quantity);
        } catch (Exception e) {
            log.error("incrRefund failed, lockOrderId: {}, bucketIndex: {}",
                    lockOrderId, bucketIndex, e);
            return -1;
        }
    }

    @Override
    public void cleanupBuckets(String lockOrderId, int bucketCount) {
        try {
            List<Object> keys = new ArrayList<>();
            for (int i = 0; i < bucketCount; i++) {
                keys.add(bucketKey(lockOrderId, i));
            }
            keys.add(metaKey(lockOrderId));
            keys.add(totalRemainingKey(lockOrderId));

            String script = loadScript("lua/cleanup_buckets.lua");
            RScript rScript = redissonClient.getScript();
            rScript.eval(RScript.Mode.READ_WRITE, script,
                    RScript.ReturnType.INTEGER, keys);
        } catch (Exception e) {
            log.error("cleanupBuckets failed, lockOrderId: {}", lockOrderId, e);
        }
    }

    @Override
    public int getTotalRemaining(String lockOrderId) {
        try {
            String key = totalRemainingKey(lockOrderId);
            Object value = redissonClient.getBucket(key).get();
            if (value == null) {
                return -1;
            }
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            log.error("getTotalRemaining failed, lockOrderId: {}", lockOrderId, e);
            return -1;
        }
    }

    @Override
    public boolean isBucketMetaValid(String lockOrderId) {
        try {
            return redissonClient.getBucket(metaKey(lockOrderId)).isExists();
        } catch (Exception e) {
            log.error("isBucketMetaValid failed, lockOrderId: {}", lockOrderId, e);
            return false;
        }
    }

    private String loadScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
