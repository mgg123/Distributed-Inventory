package com.mgg.exp.store.domain.routing.valueobject;

public final class RouteResolveResult {

    private final boolean found;
    private final String lockOrderId;
    private final boolean fromCache;

    private RouteResolveResult(boolean found, String lockOrderId, boolean fromCache) {
        this.found = found;
        this.lockOrderId = lockOrderId;
        this.fromCache = fromCache;
    }

    public static RouteResolveResult found(String lockOrderId, boolean fromCache) {
        return new RouteResolveResult(true, lockOrderId, fromCache);
    }

    public static RouteResolveResult notFound() {
        return new RouteResolveResult(false, null, false);
    }

    public boolean isFound() {
        return found;
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public boolean isFromCache() {
        return fromCache;
    }
}
