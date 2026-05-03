package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import com.mgg.exp.store.common.exception.MergeCommitFailedException;
import com.mgg.exp.store.common.util.IdGenerator;
import com.mgg.exp.store.domain.deduction.event.MergeCommittedEvent;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import com.mgg.exp.store.domain.gateway.DistributedLockGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import com.mgg.exp.store.infrastructure.event.DomainEventPublisherImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMergeAppServiceImpl implements InventoryMergeAppService {

    private final InventoryRepository inventoryRepository;
    private final LockOrderRepository lockOrderRepository;
    private final DeductionDetailRepository deductionDetailRepository;
    private final RedisBucketGateway redisBucketGateway;
    private final DistributedLockGateway distributedLockGateway;
    private final DomainEventPublisherImpl eventPublisher;
    private final StoreProperties storeProperties;

    @Override
    public MergeResult mergeCommit(String lockOrderId) {
        String lockKey = "merge:" + lockOrderId;
        boolean locked = distributedLockGateway.tryLock(lockKey,
                storeProperties.getLock().getWaitTimeSeconds(),
                storeProperties.getLock().getLeaseTimeSeconds());
        if (!locked) {
            log.warn("merge lock not acquired, lockOrderId: {}", lockOrderId);
            return MergeResult.failed("MERGE_IN_PROGRESS");
        }

        try {
            Optional<LockInventoryOrder> orderOpt = lockOrderRepository
                    .findById(new LockOrderId(lockOrderId));
            if (orderOpt.isEmpty() || !orderOpt.get().isActive()) {
                return MergeResult.noPending();
            }

            LockInventoryOrder order = orderOpt.get();
            boolean metaValid = redisBucketGateway.isBucketMetaValid(lockOrderId);
            if (metaValid) {
                redisBucketGateway.cleanupBuckets(lockOrderId,
                        storeProperties.getBucket().getCount());
            }

            String batchId = IdGenerator.nextIdStr();
            MergeResult result = executeMergeTransaction(lockOrderId, batchId, order);

            if (result.isSuccess()) {
                distributedLockGateway.unlock(lockKey);

                redisBucketGateway.cleanupBuckets(lockOrderId,
                        storeProperties.getBucket().getCount());
                lockOrderRepository.updateMergeCompleted(new LockOrderId(lockOrderId));

                eventPublisher.publish(new MergeCommittedEvent(
                        lockOrderId, order.getSkuId().value(),
                        order.getLockQuantity().getValue()));
            }
            return result;
        } finally {
            if (distributedLockGateway.isHeldByCurrentThread(lockKey)) {
                distributedLockGateway.unlock(lockKey);
            }
        }
    }

    @Override
    public MergeResult compensateMerge(String lockOrderId) {
        return mergeCommit(lockOrderId);
    }

    @Transactional
    protected MergeResult executeMergeTransaction(String lockOrderId, String batchId,
                                                    LockInventoryOrder order) {
        int marked = deductionDetailRepository.markPendingAsMerged(lockOrderId, batchId);
        if (marked == 0) {
            log.info("no pending details to merge, lockOrderId: {}", lockOrderId);
            return MergeResult.noPending();
        }

        Integer netDeduction = deductionDetailRepository.calculateNetDeduction(batchId);
        if (netDeduction == null || netDeduction == 0) {
            return MergeResult.noPending();
        }

        int currentLockQuantity = order.getLockQuantity().getValue();
        SkuId skuId = order.getSkuId();

        int updated = inventoryRepository.mergeCommit(skuId, netDeduction, currentLockQuantity);
        if (updated == 0) {
            throw new MergeCommitFailedException("SQ_OR_LQ_INSUFFICIENT");
        }

        lockOrderRepository.updateStatusToArchived(new LockOrderId(lockOrderId));

        log.info("mergeCommit success, lockOrderId: {}, skuId: {}, netDeduction: {}, " +
                        "currentLockQuantity: {}", lockOrderId, skuId.value(),
                netDeduction, currentLockQuantity);
        return MergeResult.success();
    }
}
