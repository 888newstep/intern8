package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheEvictionTest {

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
    void deleteDynamic_ShouldEvictCache() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, org.mockito.Mockito.mock(vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper.class), notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long dynamicId = 100L;
        
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(dynamicId);
        dynamic.setUserId(userId);

        when(dynamicMapper.selectById(dynamicId)).thenReturn(dynamic);

        dynamicService.deleteDynamic(userId, dynamicId);

        verify(redisCacheService).evictWithDoubleDelete("dynamic:detail:" + dynamicId, 500L);
        verify(dynamicMapper).deleteById(dynamicId);
    }

    @Test
    void likeDynamic_ShouldEvictCache() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, org.mockito.Mockito.mock(vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper.class), notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long dynamicId = 100L;
        
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(dynamicId);
        dynamic.setUserId(2L);

        when(dynamicMapper.selectById(dynamicId)).thenReturn(dynamic);
        when(likeMapper.selectByUserAndTarget(userId, dynamicId, 1)).thenReturn(null);
        when(likeMapper.insert(any())).thenReturn(1);
        when(dynamicMapper.updateLikeCount(dynamicId)).thenReturn(1);
        when(lockService.executeWithLockVoid(anyString(), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });

        dynamicService.likeDynamic(userId, dynamicId);

        verify(redisCacheService).evictWithDoubleDelete("dynamic:detail:" + dynamicId, 500L);
    }

    @Test
    void commentDynamic_ShouldEvictCache() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, org.mockito.Mockito.mock(vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper.class), notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long dynamicId = 100L;
        
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(dynamicId);
        dynamic.setUserId(2L);

        when(dynamicMapper.selectById(dynamicId)).thenReturn(dynamic);
        when(lockService.executeWithLockVoid(anyString(), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });

        dynamicService.commentDynamic(userId, dynamicId, "test comment");

        verify(redisCacheService).evictWithDoubleDelete("dynamic:detail:" + dynamicId, 500L);
    }

    @Test
    void shareDynamic_ShouldEvictCache() {
        DynamicServiceImpl dynamicService = new DynamicServiceImpl(
            dynamicMapper, followMapper, org.mockito.Mockito.mock(vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper.class), notificationService, lockService,
            rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        Long userId = 1L;
        Long dynamicId = 100L;
        
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(dynamicId);
        dynamic.setUserId(2L);

        when(dynamicMapper.selectById(dynamicId)).thenReturn(dynamic);

        dynamicService.shareDynamic(userId, dynamicId);

        verify(redisCacheService).evictWithDoubleDelete("dynamic:detail:" + dynamicId, 500L);
    }
}
