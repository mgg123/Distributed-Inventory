package com.mgg.exp.store.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mgg.exp.store.infrastructure.dataobject.RefundDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundDetailMapper extends BaseMapper<RefundDetailPO> {

    @Select("SELECT id, sku_id, refund_quantity, deduct_path, status, order_id, " +
            "ref_detail_id, refund_request_id, create_time " +
            "FROM refund_detail WHERE ref_detail_id = #{refDetailId} " +
            "AND refund_request_id = #{refundRequestId}")
    RefundDetailPO selectByRefDetailAndRequestId(@Param("refDetailId") String refDetailId,
                                                  @Param("refundRequestId") String refundRequestId);
}
