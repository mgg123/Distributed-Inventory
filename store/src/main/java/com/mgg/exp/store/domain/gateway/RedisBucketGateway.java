package com.mgg.exp.store.domain.gateway;

public interface RedisBucketGateway {

    boolean initBuckets(String lockOrderId, Long skuId, int bucketCount, int quantityPerBucket);

    int deduct(String lockOrderId, int bucketIndex, int quantity);

    int incrRefund(String lockOrderId, int bucketIndex, int quantity);

    void cleanupBuckets(String lockOrderId, int bucketCount);

    int getTotalRemaining(String lockOrderId);

    boolean isBucketMetaValid(String lockOrderId);
}
