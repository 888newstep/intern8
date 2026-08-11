package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.utils.RedisCommandClient;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUtilTest {

    private final RedisCommandClient redisCommandClient = mock(RedisCommandClient.class);
    private final RedisUtil redisUtil = new RedisUtil(redisCommandClient);

    @Test
    void getCountReadsNumericValueFromUnifiedClient() {
        when(redisCommandClient.get("notice:count:1")).thenReturn("7");

        Long count = redisUtil.getCount("notice:count:1");

        assertEquals(7L, count);
        verify(redisCommandClient).get("notice:count:1");
    }

    @Test
    void getCountFallsBackToZeroWhenMissing() {
        when(redisCommandClient.get("notice:count:2")).thenReturn(null);

        Long count = redisUtil.getCount("notice:count:2");

        assertEquals(0L, count);
    }

    @Test
    void getReturnsNullWhenClientThrows() {
        when(redisCommandClient.get("broken")).thenThrow(new RuntimeException("boom"));

        String value = redisUtil.get("broken");

        assertNull(value);
    }

    @Test
    void evalStrictPropagatesScriptFailureToCachePolicy() {
        RuntimeException failure = new RuntimeException("script failed");
        when(redisCommandClient.eval(anyString(), anyList(), anyList())).thenThrow(failure);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> redisUtil.evalStrict("return 1", java.util.List.of("key"),
                        java.util.List.of("arg")));

        assertEquals(failure, actual);
    }
}
