package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.utils.RedisCommandClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiIdempotencyServiceTest {

    @Mock
    private RedisCommandClient redisCommandClient;

    @Test
    void missingKey_ShouldKeepBackwardCompatibleExecution() {
        ApiIdempotencyService service = newService();
        AtomicInteger executions = new AtomicInteger();

        ResponseDO response = service.execute(1L, "POST:/api/dynamic/publish", null,
                Map.of("content", "hello"), () -> {
                    executions.incrementAndGet();
                    return ResponseDO.success("ok");
                });

        assertTrue(response.isSuccess());
        assertEquals(1, executions.get());
        verify(redisCommandClient, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void redisUnavailable_ShouldFailClosedWithoutExecutingBusinessOperation() {
        ApiIdempotencyService service = newService();
        AtomicInteger executions = new AtomicInteger();
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenReturn(null);

        ResponseDO response = service.execute(1L, "POST:/api/dynamic/publish", "request-1",
                Map.of("content", "hello"), () -> {
                    executions.incrementAndGet();
                    return ResponseDO.success("ok");
                });

        assertEquals(503, response.getErrorCode());
        assertEquals(0, executions.get());
    }

    @Test
    void sameKeyWithDifferentPayload_ShouldReturnConflict() {
        ApiIdempotencyService service = newService();
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        ResponseDO response = service.execute(1L, "POST:/api/dynamic/publish", "request-1",
                Map.of("content", "different"), () -> ResponseDO.success("should not run"));

        assertEquals(409, response.getErrorCode());
    }

    @Test
    void processingRequest_ShouldReturnConflictWithoutExecutingAgain() {
        ApiIdempotencyService service = newService();
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        ResponseDO response = service.execute(1L, "POST:/api/dynamic/publish", "request-1",
                Map.of("content", "hello"), () -> ResponseDO.success("should not run"));

        assertEquals(409, response.getErrorCode());
    }

    @Test
    void completedRequest_ShouldReturnCachedResponse() throws Exception {
        ApiIdempotencyService service = newService();
        String cached = new ObjectMapper().writeValueAsString(ResponseDO.success("cached"));
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenReturn(2L);
        when(redisCommandClient.hget(anyString(), eq("response"))).thenReturn(cached);

        AtomicInteger executions = new AtomicInteger();
        ResponseDO response = service.execute(1L, "POST:/api/dynamic/publish", "request-1",
                Map.of("content", "hello"), () -> {
                    executions.incrementAndGet();
                    return ResponseDO.success("should not run");
                });

        assertTrue(response.isSuccess());
        assertEquals("cached", response.getData());
        assertEquals(0, executions.get());
    }

    @Test
    void operationFailure_ShouldPersistFailedStateAndRethrow() {
        ApiIdempotencyService service = newService();
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        IllegalStateException failure = new IllegalStateException("database unavailable");

        assertThrows(IllegalStateException.class, () -> service.execute(
                1L, "POST:/api/dynamic/publish", "request-1", Map.of("content", "hello"), () -> {
                    throw failure;
                }));

        verify(redisCommandClient, org.mockito.Mockito.times(2))
                .eval(anyString(), anyList(), anyList());
    }

    private ApiIdempotencyService newService() {
        return new ApiIdempotencyService(
                redisCommandClient,
                new ObjectMapper(),
                new SimpleMeterRegistry());
    }
}
