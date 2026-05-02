package com.mgg.exp.store.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mgg.exp.store.infrastructure.dataobject.InventoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<InventoryPO> {

    @Update("UPDATE inventory SET lq = lq + #{actualLockQuantity} " +
            "WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity}")
    int lockInventory(@Param("skuId") Long skuId,
                      @Param("actualLockQuantity") Integer actualLockQuantity);

    @Update("UPDATE inventory SET sq = sq - #{netDeduction}, wq = wq + #{netDeduction}, " +
            "lq = lq - #{currentLockQuantity} " +
            "WHERE id = #{skuId} AND sq >= #{netDeduction} AND lq >= #{currentLockQuantity}")
    int mergeCommit(@Param("skuId") Long skuId,
                    @Param("netDeduction") Integer netDeduction,
                    @Param("currentLockQuantity") Integer currentLockQuantity);

    @Update("UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity} " +
            "WHERE id = #{skuId} AND sq - lq >= #{quantity}")
    int directDeduct(@Param("skuId") Long skuId,
                     @Param("quantity") Integer quantity);

    @Update("UPDATE inventory SET sq = sq - #{netDeduction}, wq = wq + #{netDeduction} " +
            "WHERE id = #{skuId} AND sq >= #{netDeduction}")
    int compensateMerge(@Param("skuId") Long skuId,
                        @Param("netDeduction") Integer netDeduction);

    @Update("UPDATE inventory SET wq = wq - #{quantity}, oq = oq + #{quantity} " +
            "WHERE id = #{skuId} AND wq >= #{quantity}")
    int confirmPayment(@Param("skuId") Long skuId,
                       @Param("quantity") Integer quantity);

    @Update("UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity} " +
            "WHERE id = #{skuId} AND wq >= #{quantity}")
    int cancelMerged(@Param("skuId") Long skuId,
                     @Param("quantity") Integer quantity);

    @Update("UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity} " +
            "WHERE id = #{skuId} AND oq >= #{quantity}")
    int refundOccupied(@Param("skuId") Long skuId,
                       @Param("quantity") Integer quantity);

    @Update("UPDATE inventory SET lq = 0 WHERE id = #{skuId}")
    int emergencyResetLq(@Param("skuId") Long skuId);
}
