package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.query.LockOrderVO;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LockOrderQueryExe {

    private final LockOrderRepository lockOrderRepository;

    public LockOrderVO queryByLockOrderId(String lockOrderId) {
        Optional<LockInventoryOrder> orderOpt = lockOrderRepository.findById(
                new LockOrderId(lockOrderId));
        return orderOpt.map(this::toVO).orElse(null);
    }

    private LockOrderVO toVO(LockInventoryOrder order) {
        LockOrderVO vo = new LockOrderVO();
        vo.setLockOrderId(order.getId().value());
        vo.setSkuId(order.getSkuId().value());
        vo.setLockQuantity(order.getLockQuantity().getValue());
        vo.setBucketInfo(order.getBucketInfo());
        vo.setExpireTime(order.getExpireTime() != null
                ? order.getExpireTime().toString() : null);
        vo.setStatus(order.getStatus().name());
        vo.setIdempotentKey(order.getIdempotentKey());
        vo.setMergeCompleted(order.isMergeCompleted());
        return vo;
    }
}
