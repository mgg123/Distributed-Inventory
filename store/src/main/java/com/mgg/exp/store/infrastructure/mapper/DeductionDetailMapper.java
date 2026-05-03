package com.mgg.exp.store.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mgg.exp.store.infrastructure.dataobject.DeductionDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeductionDetailMapper extends BaseMapper<DeductionDetailPO> {

    @Update("UPDATE deduction_detail SET status = 'MERGED', merge_batch_id = #{batchId}, " +
            "update_time = NOW() " +
            "WHERE lock_order_id = #{lockOrderId} AND status = 'PENDING' AND merge_batch_id IS NULL")
    int markPendingAsMerged(@Param("lockOrderId") String lockOrderId,
                            @Param("batchId") String batchId);

    @Select("SELECT COALESCE(SUM(quantity), 0) AS net_deduction " +
            "FROM deduction_detail WHERE merge_batch_id = #{batchId}")
    Integer calculateNetDeduction(@Param("batchId") String batchId);

    @Update("UPDATE deduction_detail SET status = 'CANCELLED', update_time = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int cancelPending(@Param("id") String id);

    @Update("UPDATE deduction_detail SET status = 'CANCELLED', update_time = NOW() " +
            "WHERE id = #{id} AND status = 'MERGED'")
    int cancelMerged(@Param("id") String id);

    @Update("UPDATE deduction_detail SET status = 'OCCUPIED', update_time = NOW() " +
            "WHERE id = #{id} AND status = 'MERGED'")
    int confirmOccupied(@Param("id") String id);

    @Update("UPDATE deduction_detail SET status = 'REFUNDED', update_time = NOW() " +
            "WHERE id = #{id} AND status = 'OCCUPIED'")
    int refundOccupied(@Param("id") String id);

    @Select("SELECT id, sku_id, quantity, deduct_path, bucket_index, status, " +
            "order_id, lock_order_id, merge_batch_id, create_time, update_time " +
            "FROM deduction_detail WHERE order_id = #{orderId} AND sku_id = #{skuId} " +
            "LIMIT 1")
    DeductionDetailPO selectByOrderIdAndSkuId(@Param("orderId") String orderId,
                                               @Param("skuId") Long skuId);
}
