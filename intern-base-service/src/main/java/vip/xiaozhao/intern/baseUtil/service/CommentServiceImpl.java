package vip.xiaozhao.intern.baseUtil.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiLike;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.CommentService;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;

import java.util.Date;
import java.util.List;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LoggerFactory.getLogger(CommentServiceImpl.class);
    private static final String DYNAMIC_DETAIL_CACHE_PREFIX = "dynamic:detail:";

    private final TuiCommentMapper commentMapper;
    private final TuiDynamicMapper dynamicMapper;
    private final NotificationService notificationService;
    private final DistributedLockService lockService;
    private final TuiLikeMapper likeMapper;
    private final RedisCacheService redisCacheService;
    private final MqOutboxService mqOutboxService;

    @Autowired
    public CommentServiceImpl(TuiCommentMapper commentMapper, TuiDynamicMapper dynamicMapper,
                              NotificationService notificationService, DistributedLockService lockService,
                              TuiLikeMapper likeMapper, RedisCacheService redisCacheService,
                              MqOutboxService mqOutboxService) {
        this.commentMapper = commentMapper;
        this.dynamicMapper = dynamicMapper;
        this.notificationService = notificationService;
        this.lockService = lockService;
        this.likeMapper = likeMapper;
        this.redisCacheService = redisCacheService;
        this.mqOutboxService = mqOutboxService;
    }

    /**
     * Compatibility constructor for focused unit tests without an outbox bean.
     */
    public CommentServiceImpl(TuiCommentMapper commentMapper, TuiDynamicMapper dynamicMapper,
                              NotificationService notificationService, DistributedLockService lockService,
                              TuiLikeMapper likeMapper, RedisCacheService redisCacheService) {
        this(commentMapper, dynamicMapper, notificationService, lockService, likeMapper,
                redisCacheService, null);
    }

    @Override
    @Transactional(timeout = 5)
    public void addComment(Long userId, Long dynamicId, String content, Long parentId, Long replyUserId) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_COMMENT + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }

            dynamicMapper.updateCommentCount(dynamicId);

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

            evictDynamicDetailAfterCommit(dynamicId);

            final Long targetUserId = dynamic.getUserId();
            if (!userId.equals(targetUserId)) {
                sendNotificationAfterCommit(targetUserId, userId, 2, "Commented: " + content, String.valueOf(dynamicId));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Operation too frequent, please retry later");
        }
    }

    @Override
    public List<TuiComment> getCommentList(Long dynamicId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return commentMapper.selectByDynamicId(dynamicId, actualCursor, actualLimit);
    }

    @Override
    @Transactional(timeout = 5)
    public void likeComment(Long userId, Long commentId) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_COMMENT + userId + ":comment:" + commentId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiComment comment = commentMapper.selectById(commentId);
            if (comment == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "Comment not found");
            }

            TuiLike existingLike = likeMapper.selectByUserAndTarget(userId, commentId, 2);
            if (existingLike != null) {
                throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Already liked");
            }

            TuiLike like = new TuiLike();
            like.setUserId(userId);
            like.setTargetId(commentId);
            like.setTargetType(2);
            like.setStatus(0);
            like.setCreateTime(new Date());
            like.setUpdateTime(new Date());
            try {
                likeMapper.insert(like);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Already liked");
            }

            commentMapper.updateLikeCount(commentId);

            final Long targetUserId = comment.getUserId();
            if (!userId.equals(targetUserId)) {
                sendNotificationAfterCommit(targetUserId, userId, 1, "Liked your comment", String.valueOf(commentId));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Operation too frequent, please retry later");
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void deleteComment(Long userId, Long commentId) {
        TuiComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "Comment not found");
        }
        if (!userId.equals(comment.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "No permission to delete this comment");
        }
        commentMapper.deleteById(commentId);

        evictDynamicDetailAfterCommit(comment.getDynamicId());
    }

    @Override
    public int countByDynamicId(Long dynamicId) {
        Integer count = commentMapper.countByDynamicId(dynamicId);
        return count == null ? 0 : count;
    }

    private void evictDynamicDetailAfterCommit(Long dynamicId) {
        String cacheKey = DYNAMIC_DETAIL_CACHE_PREFIX + dynamicId;
        TransactionHooks.afterCommit(() -> redisCacheService.evictWithDoubleDelete(cacheKey, 500));
    }

    private void sendNotificationAfterCommit(Long userId, Long senderId, Integer type, String content, String targetId) {
        NotificationEvent event = NotificationEvent.create(userId, senderId, type, content, targetId);
        if (enqueueOutbox(event, RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_ROUTING_KEY)) {
            return;
        }
        TransactionHooks.afterCommit(
                () -> notificationService.sendNotification(userId, senderId, type, content, targetId));
    }

    private boolean enqueueOutbox(BaseMqEvent event, String exchangeName, String routingKey) {
        return mqOutboxService != null && mqOutboxService.enqueue(event, exchangeName, routingKey);
    }
}
