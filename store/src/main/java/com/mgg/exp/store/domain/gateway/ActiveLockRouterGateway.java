package com.mgg.exp.store.domain.gateway;

import java.util.List;

public interface ActiveLockRouterGateway {

    void setActiveLock(Long skuId, String lockOrderId);

    String getActiveLock(Long skuId);

    void addToHistory(Long skuId, String lockOrderId);

    List<String> getHistory(Long skuId);

    void removeFromHistory(Long skuId, String lockOrderId);

    void removeActiveLock(Long skuId);
}
