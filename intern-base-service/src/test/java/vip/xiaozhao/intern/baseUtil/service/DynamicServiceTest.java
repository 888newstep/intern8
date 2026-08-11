package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiFollow;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicServiceTest {

    @Mock
    private TuiDynamicMapper dynamicMapper;

    @Mock
    private TuiFollowMapper followMapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private DistributedLockService lockService;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private RabbitMQSender rabbitMQSender;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper commentMapper;

    @Mock
    private vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper likeMapper;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @InjectMocks
    private DynamicServiceImpl dynamicService;

    private TuiDynamic testDynamic;
    private TuiFollow testFollow;

    @BeforeEach
    void setUp() {
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        testDynamic = new TuiDynamic();
        testDynamic.setId(1L);
        testDynamic.setUserId(100L);
        testDynamic.setContent("Test content");
        testDynamic.setLikeCount(10);
        testDynamic.setCommentCount(5);
        testDynamic.setShareCount(3);
        testDynamic.setStatus(0);

        testFollow = new TuiFollow();
        testFollow.setId(1L);
        testFollow.setUserId(100L);
        testFollow.setFollowUserId(200L);
        testFollow.setStatus(0);
    }

    private void setupLockMock() {
        when(lockService.executeWithLockVoid(anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    task.run();
                    return true;
                });
    }

    @Test
    void getDynamicById_Success() {
        when(redisCacheService.get(anyString(), eq(TuiDynamic.class), any(java.util.function.Supplier.class)))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<TuiDynamic> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
        when(dynamicMapper.selectById(1L)).thenReturn(testDynamic);

        TuiDynamic result = dynamicService.getDynamicById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Test content", result.getContent());
    }

    @Test
    void getDynamicById_NotFound() {
        when(redisCacheService.get(anyString(), eq(TuiDynamic.class), any(java.util.function.Supplier.class)))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<TuiDynamic> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
        when(dynamicMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> dynamicService.getDynamicById(999L));
    }

    @Test
    void likeDynamic_Success() {
        setupLockMock();
        when(dynamicMapper.selectById(1L)).thenReturn(testDynamic);

        assertDoesNotThrow(() -> dynamicService.likeDynamic(300L, 1L));

        verify(dynamicMapper, times(1)).selectById(1L);
        verify(dynamicMapper, times(1)).updateLikeCount(1L);
        verify(notificationService, times(1)).sendNotification(
                eq(100L), eq(300L), eq(1), anyString(), anyString()
        );
    }

    @Test
    void likeDynamic_SelfLike_NoNotification() {
        setupLockMock();
        when(dynamicMapper.selectById(1L)).thenReturn(testDynamic);

        assertDoesNotThrow(() -> dynamicService.likeDynamic(100L, 1L));

        verify(dynamicMapper, times(1)).selectById(1L);
        verify(dynamicMapper, times(1)).updateLikeCount(1L);
        verify(notificationService, never()).sendNotification(any(), any(), any(), any(), any());
    }

    @Test
    void likeDynamic_NotFound() {
        setupLockMock();
        when(dynamicMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> dynamicService.likeDynamic(100L, 999L));
    }

    @Test
    void follow_Success() {
        setupLockMock();
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(null);

        assertDoesNotThrow(() -> dynamicService.follow(100L, 200L));

        verify(followMapper, times(1)).selectByUserAndFollow(100L, 200L);
        verify(followMapper, times(1)).insert(any(TuiFollow.class));
        verify(notificationService, times(1)).sendNotification(
                eq(200L), eq(100L), eq(4), anyString(), anyString()
        );
    }

    @Test
    void follow_Self_Exception() {
        assertThrows(BusinessException.class, () -> dynamicService.follow(100L, 100L));
        verify(followMapper, never()).selectByUserAndFollow(any(), any());
        verify(followMapper, never()).insert(any());
    }

    @Test
    void follow_AlreadyFollowed_Exception() {
        setupLockMock();
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(testFollow);

        assertThrows(BusinessException.class, () -> dynamicService.follow(100L, 200L));
        verify(followMapper, times(1)).selectByUserAndFollow(100L, 200L);
        verify(followMapper, never()).insert(any());
    }

    @Test
    void unfollow_Success() {
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(testFollow);

        assertDoesNotThrow(() -> dynamicService.unfollow(100L, 200L));

        verify(followMapper, times(1)).selectByUserAndFollow(100L, 200L);
        verify(followMapper, times(1)).deleteByUserAndFollow(100L, 200L);
    }

    @Test
    void unfollow_NotFollowed_Exception() {
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> dynamicService.unfollow(100L, 200L));
    }

    @Test
    void isFollowing_True() {
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(testFollow);

        Boolean result = dynamicService.isFollowing(100L, 200L);

        assertTrue(result);
    }

    @Test
    void isFollowing_False() {
        when(followMapper.selectByUserAndFollow(100L, 200L)).thenReturn(null);

        Boolean result = dynamicService.isFollowing(100L, 200L);

        assertFalse(result);
    }

    @Test
    void countFollowers_Success() {
        when(followMapper.countFollowers(100L)).thenReturn(50);

        Integer result = dynamicService.countFollowers(100L);

        assertEquals(50, result);
    }

    @Test
    void countFollowers_Null() {
        when(followMapper.countFollowers(100L)).thenReturn(null);

        Integer result = dynamicService.countFollowers(100L);

        assertEquals(0, result);
    }

    @Test
    void getFeed_Success() {
        List<TuiDynamic> dynamics = new ArrayList<>();
        dynamics.add(testDynamic);
        when(dynamicMapper.selectFeedDynamicIds(100L, Long.MAX_VALUE, 20))
                .thenReturn(List.of(1L));
        when(dynamicMapper.selectByIds(List.of(1L))).thenReturn(dynamics);

        List<TuiDynamic> result = dynamicService.getFeed(100L, null, null);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(dynamicMapper, times(1)).selectFeedDynamicIds(100L, Long.MAX_VALUE, 20);
        verify(dynamicMapper, times(1)).selectByIds(List.of(1L));
    }

    @Test
    void getFeed_ShouldRestoreIdQueryOrderAndSkipRowsDeletedBeforeHydration() {
        TuiDynamic first = new TuiDynamic();
        first.setId(10L);
        TuiDynamic second = new TuiDynamic();
        second.setId(20L);

        when(dynamicMapper.selectFeedDynamicIds(100L, 50L, 3))
                .thenReturn(List.of(30L, 20L, 10L));
        // 回表顺序不保证与 ID 查询一致，且 30L 模拟在两阶段之间被删除。
        when(dynamicMapper.selectByIds(List.of(30L, 20L, 10L)))
                .thenReturn(List.of(first, second));

        List<TuiDynamic> result = dynamicService.getFeed(100L, 50L, 3);

        assertEquals(List.of(20L, 10L), result.stream().map(TuiDynamic::getId).toList());
    }

    @Test
    void getFeed_ShouldNotHydrateWhenNoCandidateIds() {
        when(dynamicMapper.selectFeedDynamicIds(100L, Long.MAX_VALUE, 20))
                .thenReturn(List.of());

        assertTrue(dynamicService.getFeed(100L, null, null).isEmpty());
        verify(dynamicMapper, never()).selectByIds(anyList());
    }

    @Test
    void deleteDynamic_Success() {
        when(dynamicMapper.selectById(1L)).thenReturn(testDynamic);

        assertDoesNotThrow(() -> dynamicService.deleteDynamic(100L, 1L));

        verify(dynamicMapper, times(1)).selectById(1L);
        verify(dynamicMapper, times(1)).deleteById(1L);
    }

    @Test
    void deleteDynamic_NotOwner_Exception() {
        when(dynamicMapper.selectById(1L)).thenReturn(testDynamic);

        assertThrows(BusinessException.class, () -> dynamicService.deleteDynamic(300L, 1L));
    }
}
