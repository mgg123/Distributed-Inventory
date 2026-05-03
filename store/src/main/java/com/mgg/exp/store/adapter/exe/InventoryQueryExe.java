package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.query.InventoryVO;
import com.mgg.exp.store.domain.inventory.aggregate.InventoryAggregate;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryQueryExe {

    private final InventoryRepository inventoryRepository;

    public InventoryVO queryInventory(Long skuId) {
        return inventoryRepository.findBySkuId(new SkuId(skuId))
                .map(this::toVO)
                .orElse(null);
    }

    private InventoryVO toVO(InventoryAggregate aggregate) {
        InventoryVO vo = new InventoryVO();
        vo.setSkuId(aggregate.getSkuId().value());
        vo.setSq(aggregate.getSq().getValue());
        vo.setWq(aggregate.getWq().getValue());
        vo.setOq(aggregate.getOq().getValue());
        vo.setLq(aggregate.getLq().getValue());
        vo.setAvailableQuantity(aggregate.getAvailableQuantity().getValue());
        return vo;
    }
}
