package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;

public interface InventoryMergeAppService {

    MergeResult mergeCommit(String lockOrderId);

    MergeResult compensateMerge(String lockOrderId);
}
