package com.mgg.exp.store.infrastructure.converter;

import com.mgg.exp.store.infrastructure.dataobject.InventoryPO;
import org.springframework.stereotype.Component;

@Component
public class InventoryConverter {

    public InventoryPO toInventoryPO(Long skuId, Integer sq, Integer wq, Integer oq, Integer lq) {
        InventoryPO po = new InventoryPO();
        po.setId(skuId);
        po.setSq(sq);
        po.setWq(wq);
        po.setOq(oq);
        po.setLq(lq);
        return po;
    }
}
