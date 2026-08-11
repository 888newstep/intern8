package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicCacheServiceTest {

    @Mock
    private TuiDynamicMapper dynamicMapper;

    @Mock
    private RedisUtil redisUtil;

    @Mock
    private RedisCacheService redisCacheService;

    private DynamicServiceImpl dynamicService;

    @BeforeEach
    void setUp() {
        dynamicService = new DynamicServiceImpl(
                dynamicMapper,
                null,
                null,
                null,
                null,
                null,
                null,
                redisCacheService,
                null,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );
    }

    @Test
    void getDynamicById_ShouldUseCache() {
        Long dynamicId = 1L;
        TuiDynamic expectedDynamic = new TuiDynamic();
        expectedDynamic.setId(dynamicId);
        expectedDynamic.setContent("Test content");

        when(redisCacheService.get(eq("dynamic:detail:" + dynamicId), eq(TuiDynamic.class), any(Supplier.class)))
            .thenReturn(expectedDynamic);

        TuiDynamic result = dynamicService.getDynamicById(dynamicId);

        assertNotNull(result);
        assertEquals(dynamicId, result.getId());
        assertEquals("Test content", result.getContent());
        verify(redisCacheService).get(eq("dynamic:detail:" + dynamicId), eq(TuiDynamic.class), any(Supplier.class));
        verify(dynamicMapper, never()).selectById(anyLong());
    }

    @Test
    void getDynamicById_ShouldReturnCachedValue() {
        Long dynamicId = 2L;
        TuiDynamic cachedDynamic = new TuiDynamic();
        cachedDynamic.setId(dynamicId);
        cachedDynamic.setContent("Cached content");

        when(redisCacheService.get(eq("dynamic:detail:" + dynamicId), eq(TuiDynamic.class), any(Supplier.class)))
            .thenReturn(cachedDynamic);

        TuiDynamic result = dynamicService.getDynamicById(dynamicId);

        assertNotNull(result);
        assertEquals("Cached content", result.getContent());
    }
}
