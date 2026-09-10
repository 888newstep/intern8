package vip.xiaozhao.intern.baseUtil.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiLike;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeConcurrencyTest {

    @Mock
    private TuiDynamicMapper dynamicMapper;


    @Mock
    private TuiLikeMapper likeMapper;

    @Mock
    private vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper commentMapper;

    @Mock
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Mock
    private vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper followMapper;

    @Mock
    private vip.xiaozhao.intern.baseUtil.service.RabbitMQSender rabbitMQSender;

    @Mock
    private vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private DistributedLockService lockService;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DynamicServiceImpl dynamicService;

    @InjectMocks
    private CommentServiceImpl commentService;

    @Test
    void likeDynamic_ShouldPreventDuplicateLike() {
        Long userId = 1L;
        Long dynamicId = 100L;
        
        TuiDynamic dynamic = new TuiDynamic();
        dynamic.setId(dynamicId);
        dynamic.setUserId(2L);

        TuiLike existingLike = new TuiLike();
        existingLike.setUserId(userId);
        existingLike.setTargetId(dynamicId);
        existingLike.setStatus(0);

        when(dynamicMapper.selectById(dynamicId)).thenReturn(dynamic);
        when(likeMapper.selectByUserAndTarget(userId, dynamicId, 1)).thenReturn(existingLike);
        when(lockService.executeWithLockVoid(anyString(), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            dynamicService.likeDynamic(userId, dynamicId);
        });

        assertEquals("Already liked", exception.getMessage());
        verify(likeMapper, never()).insert(any());
        verify(dynamicMapper, never()).updateLikeCount(anyLong());
    }

    @Test
    void likeDynamic_ShouldAllowFirstLike() {
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

        assertDoesNotThrow(() -> dynamicService.likeDynamic(userId, dynamicId));

        verify(likeMapper).insert(any(TuiLike.class));
        verify(dynamicMapper).updateLikeCount(dynamicId);
    }

    @Test
    void likeComment_ShouldPreventDuplicateLike() {
        Long userId = 1L;
        Long commentId = 200L;
        
        TuiComment comment = new TuiComment();
        comment.setId(commentId);
        comment.setUserId(2L);

        TuiLike existingLike = new TuiLike();
        existingLike.setUserId(userId);
        existingLike.setTargetId(commentId);
        existingLike.setStatus(0);

        when(commentMapper.selectById(commentId)).thenReturn(comment);
        when(likeMapper.selectByUserAndTarget(userId, commentId, 2)).thenReturn(existingLike);
        when(lockService.executeWithLockVoid(anyString(), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            commentService.likeComment(userId, commentId);
        });

        assertEquals("Already liked", exception.getMessage());
        verify(likeMapper, never()).insert(any());
        verify(commentMapper, never()).updateLikeCount(anyLong());
    }
}
