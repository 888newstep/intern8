package vip.xiaozhao.intern.baseUtil.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiFollow;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.mq.event.ArchiveDynamicEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicServiceOutboxTest {

    @Test
    void saveDynamic_ShouldWriteArchiveEventToOutboxBeforeCommit() {
        TuiDynamicMapper dynamicMapper = mock(TuiDynamicMapper.class);
        TuiFollowMapper followMapper = mock(TuiFollowMapper.class);
        TuiCommentMapper commentMapper = mock(TuiCommentMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        RabbitMQSender rabbitMQSender = mock(RabbitMQSender.class);
        SnowflakeIdGenerator snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        TuiLikeMapper likeMapper = mock(TuiLikeMapper.class);
        MqOutboxService outboxService = mock(MqOutboxService.class);

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        }).when(lockService).executeWithLockVoid(anyString(), any(Runnable.class));
        when(snowflakeIdGenerator.nextId()).thenReturn(123L);
        when(outboxService.enqueue(any(BaseMqEvent.class), anyString(), anyString())).thenReturn(true);

        DynamicServiceImpl service = new DynamicServiceImpl(
                dynamicMapper, followMapper, commentMapper, notificationService, lockService,
                rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper,
                new SimpleMeterRegistry(), outboxService);

        service.saveDynamic(100L, "content", null);

        verify(outboxService).enqueue(any(ArchiveDynamicEvent.class),
                org.mockito.ArgumentMatchers.eq(RabbitMQConfig.ARCHIVE_DELAY_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(RabbitMQConfig.ARCHIVE_DELAY_ROUTING_KEY));
        verify(rabbitMQSender, never()).sendArchiveMessage(any(ArchiveDynamicEvent.class));
    }

    @Test
    void follow_ShouldWriteNotificationEventToOutboxInsteadOfAfterCommitPublish() {
        TuiDynamicMapper dynamicMapper = mock(TuiDynamicMapper.class);
        TuiFollowMapper followMapper = mock(TuiFollowMapper.class);
        TuiCommentMapper commentMapper = mock(TuiCommentMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        RabbitMQSender rabbitMQSender = mock(RabbitMQSender.class);
        SnowflakeIdGenerator snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        TuiLikeMapper likeMapper = mock(TuiLikeMapper.class);
        MqOutboxService outboxService = mock(MqOutboxService.class);

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        }).when(lockService).executeWithLockVoid(anyString(), any(Runnable.class));
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(null);
        when(outboxService.enqueue(any(BaseMqEvent.class), anyString(), anyString())).thenReturn(true);

        DynamicServiceImpl service = new DynamicServiceImpl(
                dynamicMapper, followMapper, commentMapper, notificationService, lockService,
                rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper,
                new SimpleMeterRegistry(), outboxService);

        service.follow(100L, 200L);

        verify(outboxService).enqueue(any(NotificationEvent.class),
                org.mockito.ArgumentMatchers.eq(RabbitMQConfig.NOTIFICATION_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(RabbitMQConfig.NOTIFICATION_ROUTING_KEY));
        verify(notificationService, never()).sendNotification(any(), any(), any(), any(), any());
    }
}
