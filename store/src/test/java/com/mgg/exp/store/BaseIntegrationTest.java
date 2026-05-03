package com.mgg.exp.store;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Set;

@SpringBootTest(classes = StoreApplication.class)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainerConfig.Initializer.class)
@Import(TestContainerConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @BeforeEach
    protected void cleanUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbcTemplate.execute("TRUNCATE TABLE deduction_detail");
        jdbcTemplate.execute("TRUNCATE TABLE refund_detail");
        jdbcTemplate.execute("TRUNCATE TABLE lock_inventory_order");
        jdbcTemplate.execute("TRUNCATE TABLE inventory");
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Set<String> keys = redisTemplate.keys("inventory:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    protected void insertInventory(long skuId, int sq, int wq, int oq, int lq) {
        jdbcTemplate.update(
                "INSERT INTO inventory (id, sq, wq, oq, lq) VALUES (?, ?, ?, ?, ?)",
                skuId, sq, wq, oq, lq);
    }
}
