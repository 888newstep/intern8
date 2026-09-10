package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiLike;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeLifecycleTest {

    private static final Long USER_ID = 1L;
    private static final Long DYNAMIC_ID = 100L;
    private static final Long COMMENT_ID = 200L;

    @Mock
    private TuiDynamicMapper dynamicMapper;
    @Mock
    private TuiCommentMapper commentMapper;
    @Mock
    private TuiFollowMapper followMapper;
    @Mock
    private TuiLikeMapper likeMapper;
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

    private DynamicServiceImpl dynamicService;
    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        dynamicService = new DynamicServiceImpl(
                dynamicMapper, followMapper, commentMapper, notificationService, lockService,
                rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        commentService = new CommentServiceImpl(
                commentMapper, dynamicMapper, notificationService, lockService,
                likeMapper, redisCacheService);

        when(lockService.executeWithLockVoid(anyString(), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });
    }

    @Test
    void likeDynamic_ShouldReactivateInactiveRelation() {
        when(dynamicMapper.selectById(DYNAMIC_ID)).thenReturn(dynamic());
        when(likeMapper.selectByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(like(1));
        when(likeMapper.reactivateByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(1);
        when(dynamicMapper.updateLikeCount(DYNAMIC_ID)).thenReturn(1);

        dynamicService.likeDynamic(USER_ID, DYNAMIC_ID);

        verify(likeMapper).reactivateByUserAndTarget(USER_ID, DYNAMIC_ID, 1);
        verify(likeMapper, never()).insert(any());
        verify(dynamicMapper).updateLikeCount(DYNAMIC_ID);
    }

    @Test
    void unlikeDynamic_ShouldDeactivateRelationAndDecrementCount() {
        when(dynamicMapper.selectById(DYNAMIC_ID)).thenReturn(dynamic());
        when(likeMapper.selectByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(like(0));
        when(likeMapper.deleteByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(1);
        when(dynamicMapper.decrementLikeCount(DYNAMIC_ID)).thenReturn(1);

        dynamicService.unlikeDynamic(USER_ID, DYNAMIC_ID);

        verify(likeMapper).deleteByUserAndTarget(USER_ID, DYNAMIC_ID, 1);
        verify(dynamicMapper).decrementLikeCount(DYNAMIC_ID);
        verify(redisCacheService).evictWithDoubleDelete("dynamic:detail:" + DYNAMIC_ID, 500L);
    }

    @Test
    void likeDynamic_ShouldFailWhenCountWasNotIncremented() {
        when(dynamicMapper.selectById(DYNAMIC_ID)).thenReturn(dynamic());
        when(likeMapper.selectByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(null);
        when(likeMapper.insert(any())).thenReturn(1);
        when(dynamicMapper.updateLikeCount(DYNAMIC_ID)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> dynamicService.likeDynamic(USER_ID, DYNAMIC_ID));

        assertEquals(500, exception.getCode());
        verify(redisCacheService, never()).evictWithDoubleDelete(anyString(), anyLong());
    }

    @Test
    void unlikeDynamic_ShouldFailWhenCountWasNotDecremented() {
        when(dynamicMapper.selectById(DYNAMIC_ID)).thenReturn(dynamic());
        when(likeMapper.selectByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(like(0));
        when(likeMapper.deleteByUserAndTarget(USER_ID, DYNAMIC_ID, 1)).thenReturn(1);
        when(dynamicMapper.decrementLikeCount(DYNAMIC_ID)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> dynamicService.unlikeDynamic(USER_ID, DYNAMIC_ID));

        assertEquals(500, exception.getCode());
        verify(redisCacheService, never()).evictWithDoubleDelete(anyString(), anyLong());
    }

    @Test
    void likeComment_ShouldReactivateInactiveRelation() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment());
        when(likeMapper.selectByUserAndTarget(USER_ID, COMMENT_ID, 2)).thenReturn(like(1));
        when(likeMapper.reactivateByUserAndTarget(USER_ID, COMMENT_ID, 2)).thenReturn(1);
        when(commentMapper.updateLikeCount(COMMENT_ID)).thenReturn(1);

        commentService.likeComment(USER_ID, COMMENT_ID);

        verify(likeMapper).reactivateByUserAndTarget(USER_ID, COMMENT_ID, 2);
        verify(likeMapper, never()).insert(any());
        verify(commentMapper).updateLikeCount(COMMENT_ID);
    }

    @Test
    void unlikeComment_ShouldDeactivateRelationAndDecrementCount() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment());
        when(likeMapper.selectByUserAndTarget(USER_ID, COMMENT_ID, 2)).thenReturn(like(0));
        when(likeMapper.deleteByUserAndTarget(USER_ID, COMMENT_ID, 2)).thenReturn(1);
        when(commentMapper.decrementLikeCount(COMMENT_ID)).thenReturn(1);

        commentService.unlikeComment(USER_ID, COMMENT_ID);

        verify(likeMapper).deleteByUserAndTarget(USER_ID, COMMENT_ID, 2);
        verify(commentMapper).decrementLikeCount(COMMENT_ID);
    }

    @Test
    void likeComment_ShouldFailWhenCountWasNotIncremented() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment());
        when(likeMapper.selectByUserAndTarget(USER_ID, COMMENT_ID, 2)).thenReturn(null);
        when(likeMapper.insert(any())).thenReturn(1);
        when(commentMapper.updateLikeCount(COMMENT_ID)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> commentService.likeComment(USER_ID, COMMENT_ID));

        assertEquals(500, exception.getCode());
    }

    private TuiDynamic dynamic() {
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(DYNAMIC_ID);
        dynamic.setUserId(USER_ID);
        dynamic.setStatus(0);
        return dynamic;
    }

    private TuiComment comment() {
        TuiComment comment = new TuiComment();
        comment.setId(COMMENT_ID);
        comment.setUserId(USER_ID);
        comment.setStatus(0);
        return comment;
    }

    private TuiLike like(int status) {
        TuiLike like = new TuiLike();
        like.setUserId(USER_ID);
        like.setStatus(status);
        return like;
    }
}
