package com.mgg.exp.store.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mgg.exp.store.infrastructure.dataobject.LockInventoryOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LockOrderMapper extends BaseMapper<LockInventoryOrderPO> {

    @Select("SELECT id, sku_id, lock_quantity, bucket_info, expire_time, status, " +
            "idempotent_key, merge_completed, create_time, update_time " +
            "FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}")
    LockInventoryOrderPO selectByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    @Select("SELECT id, sku_id, lock_quantity, bucket_info, expire_time, status, " +
            "idempotent_key, merge_completed, create_time, update_time " +
            "FROM lock_inventory_order WHERE expire_time < #{now} AND status = 'ACTIVE'")
    List<LockInventoryOrderPO> selectExpiredActive(@Param("now") LocalDateTime now);

    @Update("UPDATE lock_inventory_order SET status = 'ARCHIVED', update_time = NOW() " +
            "WHERE id = #{lockOrderId} AND status = 'ACTIVE'")
    int updateStatusToArchived(@Param("lockOrderId") String lockOrderId);

    @Update("UPDATE lock_inventory_order SET merge_completed = 1, update_time = NOW() " +
            "WHERE id = #{lockOrderId} AND merge_completed = 0")
    int updateMergeCompleted(@Param("lockOrderId") String lockOrderId);

    @Update("UPDATE lock_inventory_order SET status = 'ARCHIVED', update_time = NOW() " +
            "WHERE sku_id = #{skuId} AND status = 'ACTIVE'")
    int archiveAllBySkuId(@Param("skuId") Long skuId);
}
