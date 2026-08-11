package vip.xiaozhao.intern.baseUtil.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcTaskDecoratorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void restoresCallerContextAndDoesNotLeakTaskValues() {
        MDC.put("requestId", "captured");
        Runnable decorated = new MdcTaskDecorator().decorate(() -> {
            assertEquals("captured", MDC.get("requestId"));
            MDC.put("taskOnly", "must-not-leak");
        });

        MDC.put("requestId", "caller-changed");
        decorated.run();

        assertEquals("caller-changed", MDC.get("requestId"));
        assertNull(MDC.get("taskOnly"));
    }
}
