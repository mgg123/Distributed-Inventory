package com.mgg.exp.store;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(classes = StoreApplication.class)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainerConfig.Initializer.class)
@Import(TestContainerConfig.class)
class StoreApplicationTests {

	@Test
	void contextLoads() {
	}

}
