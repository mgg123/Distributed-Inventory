package com.mgg.exp.store.domain.gateway;

public interface EmergencyDegradeGateway {

    boolean setDegradeFlag(Long skuId);

    boolean isDegradeFlagSet(Long skuId);

    void removeDegradeFlag(Long skuId);
}
