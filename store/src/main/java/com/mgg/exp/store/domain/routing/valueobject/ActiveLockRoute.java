package com.mgg.exp.store.domain.routing.valueobject;

import java.util.List;

public final class ActiveLockRoute {

    private final String lockOrderId;
    private final List<String> historyLockOrderIds;

    public ActiveLockRoute(String lockOrderId, List<String> historyLockOrderIds) {
        this.lockOrderId = lockOrderId;
        this.historyLockOrderIds = historyLockOrderIds != null
                ? List.copyOf(historyLockOrderIds)
                : List.of();
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public List<String> getHistoryLockOrderIds() {
        return historyLockOrderIds;
    }

    public boolean hasActiveLock() {
        return lockOrderId != null && !lockOrderId.isBlank();
    }
}
