package com.mgg.exp.store.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mgg.exp.store.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiEndToEndTest extends BaseIntegrationTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private ObjectMapper objectMapper = new ObjectMapper();

        private MockMvc mockMvc;

        @Override
        @BeforeEach
        protected void cleanUp() {
                super.cleanUp();
                mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

        @Nested
        @DisplayName("15 API接口测试")
        class ApiTest {

                @Test
                @DisplayName("API-FUNC-001: POST /api/v1/inventory/lock - 正常锁库存")
                void testLockInventoryApi() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"api-lock-001\"}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andExpect(jsonPath("$.data.lockOrderId").isNotEmpty())
                                        .andExpect(jsonPath("$.data.actualLockQuantity").isNumber());
                }

                @Test
                @DisplayName("API-FUNC-002: POST /api/v1/inventory/lock - 可用额度不足")
                void testLockInventoryInsufficientApi() throws Exception {
                        insertInventory(10001L, 0, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"api-lock-insuf-001\"}"))
                                        .andExpect(jsonPath("$.code").value(102001));
                }

                @Test
                @DisplayName("API-FUNC-003: POST /api/v1/inventory/lock - 幂等命中")
                void testLockInventoryIdempotentApi() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        MvcResult first = mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"api-lock-idem-001\"}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andReturn();

                        String responseBody = first.getResponse().getContentAsString();
                        Map<?, ?> responseMap = objectMapper.readValue(responseBody, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                        String firstLockOrderId = (String) data.get("lockOrderId");

                        mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"api-lock-idem-001\"}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.data.lockOrderId").value(firstLockOrderId));
                }

                @Test
                @DisplayName("API-FUNC-005: POST /api/v1/inventory/deduct - Redis分桶扣减成功")
                void testDeductInventoryApi() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"api-deduct-lock-001\"}"))
                                        .andExpect(status().isOk());

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-API-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andExpect(jsonPath("$.data.detailId").isNotEmpty());
                }

                @Test
                @DisplayName("API-FUNC-006: POST /api/v1/inventory/deduct - DB降级扣减成功")
                void testDeductDbDegradeApi() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-API-DB-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andExpect(jsonPath("$.data.deductPath").value("DIRECT_DB"));
                }

                @Test
                @DisplayName("API-FUNC-007: POST /api/v1/inventory/deduct - 库存不足")
                void testDeductInsufficientApi() throws Exception {
                        insertInventory(10001L, 5, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-API-INSUF-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(jsonPath("$.code").value(101001));
                }

                @Test
                @DisplayName("API-FUNC-008: GET /api/v1/inventory/{skuId} - 查询库存信息")
                void testQueryInventoryApi() throws Exception {
                        insertInventory(10001L, 8500, 1000, 500, 2000);

                        mockMvc.perform(get("/api/v1/inventory/10001"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andExpect(jsonPath("$.data.sq").value(8500))
                                        .andExpect(jsonPath("$.data.wq").value(1000))
                                        .andExpect(jsonPath("$.data.oq").value(500))
                                        .andExpect(jsonPath("$.data.lq").value(2000));
                }

                @Test
                @DisplayName("API-EXCP-001: 参数校验-必填字段缺失")
                void testParamValidationMissing() throws Exception {
                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("API-EXCP-002: 参数校验-quantity<=0")
                void testParamValidationInvalid() throws Exception {
                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD\",\"skuId\":10001,\"quantity\":0}"))
                                        .andExpect(status().isBadRequest());
                }
        }

        @Nested
        @DisplayName("16 端到端场景测试")
        class EndToEndTest {

                @Test
                @DisplayName("E2E-FUNC-001: 完整链路：锁库存→扣减→合并→付款确认→退款")
                void testFullChainLockDeductMergeConfirmRefund() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        MvcResult lockResult = mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"e2e-001\"}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0))
                                        .andReturn();

                        String lockBody = lockResult.getResponse().getContentAsString();
                        Map<?, ?> lockMap = objectMapper.readValue(lockBody, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> lockData = (Map<String, Object>) lockMap.get("data");
                        String lockOrderId = (String) lockData.get("lockOrderId");

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(0));

                        mockMvc.perform(post("/api/v1/inventory/merge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"lockOrderId\":\"" + lockOrderId + "\"}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterMerge = jdbcTemplate.queryForMap(
                                        "SELECT sq, wq, lq FROM inventory WHERE id = 10001");
                        assertEquals(19990, ((Number) invAfterMerge.get("sq")).intValue());
                        assertEquals(10, ((Number) invAfterMerge.get("wq")).intValue());
                        assertEquals(0, ((Number) invAfterMerge.get("lq")).intValue());

                        mockMvc.perform(post("/api/v1/inventory/confirm")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-001\",\"skuId\":10001}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterConfirm = jdbcTemplate.queryForMap(
                                        "SELECT wq, oq FROM inventory WHERE id = 10001");
                        assertEquals(0, ((Number) invAfterConfirm.get("wq")).intValue());
                        assertEquals(10, ((Number) invAfterConfirm.get("oq")).intValue());

                        mockMvc.perform(post("/api/v1/inventory/refund")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-001\",\"skuId\":10001,\"refundQuantity\":10,\"refundRequestId\":\"REF-E2E-001\"}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterRefund = jdbcTemplate.queryForMap(
                                        "SELECT oq, sq FROM inventory WHERE id = 10001");
                        assertEquals(0, ((Number) invAfterRefund.get("oq")).intValue());
                        assertEquals(20000, ((Number) invAfterRefund.get("sq")).intValue());
                }

                @Test
                @DisplayName("E2E-FUNC-002: 完整链路：锁库存→扣减→合并→取消")
                void testFullChainCancel() throws Exception {
                        insertInventory(10001L, 20000, 0, 0, 0);

                        MvcResult lockResult = mockMvc.perform(post("/api/v1/inventory/lock")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"skuId\":10001,\"lockQuantity\":10000,\"idempotentKey\":\"e2e-cancel-001\"}"))
                                        .andExpect(status().isOk())
                                        .andReturn();

                        String lockBody = lockResult.getResponse().getContentAsString();
                        Map<?, ?> lockMap = objectMapper.readValue(lockBody, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> lockData = (Map<String, Object>) lockMap.get("data");
                        String lockOrderId = (String) lockData.get("lockOrderId");

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-CANCEL-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isOk());

                        mockMvc.perform(post("/api/v1/inventory/merge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"lockOrderId\":\"" + lockOrderId + "\"}"))
                                        .andExpect(status().isOk());

                        mockMvc.perform(post("/api/v1/inventory/cancel")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-CANCEL-001\",\"skuId\":10001}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterCancel = jdbcTemplate.queryForMap(
                                        "SELECT wq, sq FROM inventory WHERE id = 10001");
                        assertEquals(0, ((Number) invAfterCancel.get("wq")).intValue());
                        assertEquals(20000, ((Number) invAfterCancel.get("sq")).intValue());
                }

                @Test
                @DisplayName("E2E-FUNC-004: 完整链路：Redis降级→DB扣减→付款确认→退款")
                void testFullChainDbDegrade() throws Exception {
                        insertInventory(10001L, 10000, 0, 0, 0);

                        mockMvc.perform(post("/api/v1/inventory/deduct")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-DB-001\",\"skuId\":10001,\"quantity\":10}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.data.deductPath").value("DIRECT_DB"));

                        mockMvc.perform(post("/api/v1/inventory/confirm")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-DB-001\",\"skuId\":10001}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterConfirm = jdbcTemplate.queryForMap(
                                        "SELECT wq, oq FROM inventory WHERE id = 10001");
                        assertEquals(0, ((Number) invAfterConfirm.get("wq")).intValue());
                        assertEquals(10, ((Number) invAfterConfirm.get("oq")).intValue());

                        mockMvc.perform(post("/api/v1/inventory/refund")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":\"ORD-E2E-DB-001\",\"skuId\":10001,\"refundQuantity\":10,\"refundRequestId\":\"REF-E2E-DB-001\"}"))
                                        .andExpect(status().isOk());

                        Map<String, Object> invAfterRefund = jdbcTemplate.queryForMap(
                                        "SELECT oq, sq FROM inventory WHERE id = 10001");
                        assertEquals(0, ((Number) invAfterRefund.get("oq")).intValue());
                        assertEquals(10000, ((Number) invAfterRefund.get("sq")).intValue());
                }
        }
}
