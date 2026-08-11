package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedLockTest {

    @Mock
    private TuiDynamicMapper dynamicMapper;

    @Mock
    private TuiFollowMapper followMapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private DistributedLockService lockService;

    @Mock
    private RabbitMQSender rabbitMQSender;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private TuiLikeMapper likeMapper;

    @Test
    void publishDynamic_ShouldThrowExceptionWhenLockFailed() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, null, notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        String content = "test content";

        when(lockService.executeWithLockVoid(anyString(), any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            dynamicService.saveDynamic(userId, content, null);
        });

        assertNotNull(exception);
        verify(dynamicMapper, never()).insert(any());
    }

    @Test
    void likeDynamic_ShouldThrowExceptionWhenLockFailed() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, null, notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long dynamicId = 100L;

        when(lockService.executeWithLockVoid(anyString(), any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            dynamicService.likeDynamic(userId, dynamicId);
        });

        assertNotNull(exception);
        verify(likeMapper, never()).insert(any());
    }

    @Test
    void follow_ShouldThrowExceptionWhenLockFailed() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, null, notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long followUserId = 2L;

        when(lockService.executeWithLockVoid(anyString(), any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            dynamicService.follow(userId, followUserId);
        });

        assertNotNull(exception);
        verify(followMapper, never()).insert(any());
    }
}
