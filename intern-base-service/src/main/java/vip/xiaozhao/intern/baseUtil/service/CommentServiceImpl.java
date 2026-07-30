package vip.xiaozhao.intern.baseUtil.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.CommentService;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;

import java.util.Date;
import java.util.List;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LoggerFactory.getLogger(CommentServiceImpl.class);

    private final TuiCommentMapper commentMapper;
    private final TuiDynamicMapper dynamicMapper;
    private final NotificationService notificationService;
    private final DistributedLockService lockService;

    public CommentServiceImpl(TuiCommentMapper commentMapper, TuiDynamicMapper dynamicMapper,
                              NotificationService notificationService, DistributedLockService lockService) {
        this.commentMapper = commentMapper;
        this.dynamicMapper = dynamicMapper;
        this.notificationService = notificationService;
        this.lockService = lockService;
    }

    @Override
    @Transactional
    public void addComment(Long userId, Long dynamicId, String content, Long parentId, Long replyUserId) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_COMMENT + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }

            // 更新动态评论数（原子SQL）
            dynamicMapper.updateCommentCount(dynamicId);

            // 插入评论
            TuiComment comment = new TuiComment();
            comment.setDynamicId(dynamicId);
            comment.setUserId(userId);
            comment.setContent(content);
            comment.setParentId(parentId);
            comment.setReplyUserId(replyUserId);
            comment.setLikeCount(0);
            comment.setStatus(0);
            comment.setCreateTime(new Date());
            comment.setUpdateTime(new Date());
            commentMapper.insert(comment);

            // 发送通知给动态作者
            final Long targetUserId = dynamic.getUserId();
            if (!userId.equals(targetUserId)) {
                sendNotificationAfterCommit(targetUserId, userId, 2, "评论了你的动态: " + content, String.valueOf(dynamicId));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "操作太频繁，请稍后重试");
        }
    }

    @Override
    public List<TuiComment> getCommentList(Long dynamicId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.min(limit, 100);
        return commentMapper.selectByDynamicId(dynamicId, actualCursor, actualLimit);
    }

    @Override
    @Transactional
    public void likeComment(Long userId, Long commentId) {
        TuiComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "评论不存在");
        }
        commentMapper.updateLikeCount(commentId);

        // 通知评论作者
        final Long targetUserId = comment.getUserId();
        if (!userId.equals(targetUserId)) {
            sendNotificationAfterCommit(targetUserId, userId, 1, "点赞了你的评论", String.valueOf(commentId));
        }
    }

    @Override
    @Transactional
    public void deleteComment(Long userId, Long commentId) {
        TuiComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "评论不存在");
        }
        if (!userId.equals(comment.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权删除该评论");
        }
        commentMapper.deleteById(commentId);
    }

    @Override
    public int countByDynamicId(Long dynamicId) {
        Integer count = commentMapper.countByDynamicId(dynamicId);
        return count == null ? 0 : count;
    }

    private void sendNotificationAfterCommit(Long userId, Long senderId, Integer type, String content, String targetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationService.sendNotification(userId, senderId, type, content, targetId);
                }
            });
        } else {
            notificationService.sendNotification(userId, senderId, type, content, targetId);
        }
    }
}