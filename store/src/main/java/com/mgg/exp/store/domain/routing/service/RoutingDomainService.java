package com.mgg.exp.store.domain.routing.service;

import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.routing.valueobject.ActiveLockRoute;
import com.mgg.exp.store.domain.routing.valueobject.RouteResolveResult;

public class RoutingDomainService {

    private final ActiveLockRouterGateway routerGateway;

    public RoutingDomainService(ActiveLockRouterGateway routerGateway) {
        this.routerGateway = routerGateway;
    }

    public RouteResolveResult resolveActiveLock(Long skuId) {
        String lockOrderId = routerGateway.getActiveLock(skuId);
        if (lockOrderId != null && !lockOrderId.isBlank()) {
            return RouteResolveResult.found(lockOrderId, true);
        }
        return RouteResolveResult.notFound();
    }

    public ActiveLockRoute getActiveLockRoute(Long skuId) {
        String activeLock = routerGateway.getActiveLock(skuId);
        return new ActiveLockRoute(activeLock, routerGateway.getHistory(skuId));
    }
}
