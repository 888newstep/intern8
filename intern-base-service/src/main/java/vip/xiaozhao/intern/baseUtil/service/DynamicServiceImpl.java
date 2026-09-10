package vip.xiaozhao.intern.baseUtil.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiFollow;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiCommentMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiLikeMapper;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiLike;
import vip.xiaozhao.intern.baseUtil.config.RabbitMQConfig;
import vip.xiaozhao.intern.baseUtil.mq.event.ArchiveDynamicEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;
import vip.xiaozhao.intern.baseUtil.mq.event.NotificationEvent;
import vip.xiaozhao.intern.baseUtil.intf.service.DynamicService;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DynamicServiceImpl implements DynamicService {

    private static final String DYNAMIC_DETAIL_CACHE_PREFIX = "dynamic:detail:";
    private static final int DYNAMIC_LIKE_TARGET_TYPE = 1;
    private static final int LIKE_STATUS_ACTIVE = 0;
    private static final int LIKE_STATUS_INACTIVE = 1;
    private static final int FEED_CANDIDATE_MULTIPLIER = 3;
    private static final int MAX_FEED_CANDIDATES = 300;

    private final TuiDynamicMapper dynamicMapper;
    private final TuiFollowMapper followMapper;
    private final TuiCommentMapper commentMapper;
    private final NotificationService notificationService;
    private final DistributedLockService lockService;
    private final RabbitMQSender rabbitMQSender;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final RedisCacheService redisCacheService;
    private final TuiLikeMapper likeMapper;
    private final Timer feedQueryTimer;
    private final MqOutboxService mqOutboxService;

    @Autowired
    public DynamicServiceImpl(TuiDynamicMapper dynamicMapper, TuiFollowMapper followMapper,
                              TuiCommentMapper commentMapper,
                              NotificationService notificationService,
                              DistributedLockService lockService,
                              RabbitMQSender rabbitMQSender,
                              SnowflakeIdGenerator snowflakeIdGenerator,
                              RedisCacheService redisCacheService,
                              TuiLikeMapper likeMapper,
                              MeterRegistry meterRegistry,
                              MqOutboxService mqOutboxService) {
        this.dynamicMapper = dynamicMapper;
        this.followMapper = followMapper;
        this.commentMapper = commentMapper;
        this.notificationService = notificationService;
        this.lockService = lockService;
        this.rabbitMQSender = rabbitMQSender;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.redisCacheService = redisCacheService;
        this.likeMapper = likeMapper;
        this.feedQueryTimer = createFeedTimer(meterRegistry);
        this.mqOutboxService = mqOutboxService;
    }

    /**
     * Compatibility constructor for focused unit tests that do not create an
     * outbox dependency. Production wiring uses the constructor above.
     */
    public DynamicServiceImpl(TuiDynamicMapper dynamicMapper, TuiFollowMapper followMapper,
                              TuiCommentMapper commentMapper,
                              NotificationService notificationService,
                              DistributedLockService lockService,
                              RabbitMQSender rabbitMQSender,
                              SnowflakeIdGenerator snowflakeIdGenerator,
                              RedisCacheService redisCacheService,
                              TuiLikeMapper likeMapper,
                              MeterRegistry meterRegistry) {
        this(dynamicMapper, followMapper, commentMapper, notificationService, lockService,
                rabbitMQSender, snowflakeIdGenerator, redisCacheService, likeMapper,
                meterRegistry, null);
    }

    private static Timer createFeedTimer(io.micrometer.core.instrument.MeterRegistry registry) {
        try {
            if (registry != null && registry.config() != null) {
                return Timer.builder("feed.query.duration")
                        .description("Time spent executing feed query")
                        .publishPercentileHistogram()
                        .register(registry);
            }
        } catch (Exception ignored) {
        }
        return Timer.builder("feed.query.duration")
                .description("Time spent executing feed query")
                .register(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Override
    @Transactional(timeout = 5)
    public void saveDynamic(Long userId, String content, String images) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_PUBLISH + userId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            long dynamicId = snowflakeIdGenerator.nextId();

            TuiDynamic dynamic = new TuiDynamic();
            dynamic.setId(dynamicId);
            dynamic.setUserId(userId);
            dynamic.setContent(content);
            dynamic.setImages(images);
            dynamic.setLikeCount(0);
            dynamic.setCommentCount(0);
            dynamic.setShareCount(0);
            dynamic.setCreateTime(new Date());
            dynamic.setUpdateTime(new Date());
            dynamic.setStatus(0);
            dynamicMapper.insert(dynamic);

            ArchiveDynamicEvent event = ArchiveDynamicEvent.create(dynamicId);
            if (!enqueueOutbox(event, RabbitMQConfig.ARCHIVE_DELAY_EXCHANGE,
                    RabbitMQConfig.ARCHIVE_DELAY_ROUTING_KEY)) {
                TransactionHooks.afterCommit(() -> rabbitMQSender.sendArchiveMessage(event));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED.getCode(),
                    ErrorCode.LOCK_ACQUIRE_FAILED.getMessage());
        }
    }

    @Override
    public TuiDynamic getDynamicById(Long id) {
        TuiDynamic dynamic = redisCacheService.get(DYNAMIC_DETAIL_CACHE_PREFIX + id, TuiDynamic.class,
                () -> dynamicMapper.selectById(id));
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        return dynamic;
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public List<TuiDynamic> getFeed(Long userId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return feedQueryTimer.record(() -> {
            int candidateLimit = Math.min(actualLimit * FEED_CANDIDATE_MULTIPLIER,
                    MAX_FEED_CANDIDATES);
            List<Long> dynamicIds = dynamicMapper.selectFeedDynamicIdsFast(
                    userId, actualCursor, candidateLimit, actualLimit);

            // 未拿满一页时无法证明候选窗口之外没有匹配项，回退保证稀疏关注场景正确。
            if (dynamicIds == null || dynamicIds.size() < actualLimit) {
                dynamicIds = dynamicMapper.selectFeedDynamicIds(
                        userId, actualCursor, actualLimit);
            }
            if (dynamicIds == null || dynamicIds.isEmpty()) {
                return List.of();
            }

            List<TuiDynamic> loadedDynamics = dynamicMapper.selectByIds(dynamicIds);
            if (loadedDynamics == null || loadedDynamics.isEmpty()) {
                return List.of();
            }

            Map<Long, TuiDynamic> dynamicsById = new HashMap<>(loadedDynamics.size());
            for (TuiDynamic dynamic : loadedDynamics) {
                if (dynamic != null && dynamic.getId() != null) {
                    dynamicsById.putIfAbsent(dynamic.getId(), dynamic);
                }
            }
            return dynamicIds.stream()
                    .map(dynamicsById::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public List<TuiDynamic> getUserDynamics(Long userId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return dynamicMapper.selectByUserId(userId, actualCursor, actualLimit);
    }

    @Override
    @Transactional(timeout = 5)
    public void likeDynamic(Long userId, Long dynamicId) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_LIKE + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }

            TuiLike existingLike = likeMapper.selectByUserAndTarget(
                    userId, dynamicId, DYNAMIC_LIKE_TARGET_TYPE);
            if (existingLike == null) {
                TuiLike like = new TuiLike();
                like.setUserId(userId);
                like.setTargetId(dynamicId);
                like.setTargetType(DYNAMIC_LIKE_TARGET_TYPE);
                like.setStatus(LIKE_STATUS_ACTIVE);
                like.setCreateTime(new Date());
                like.setUpdateTime(new Date());
                try {
                    requireSingleRow(likeMapper.insert(like), "create dynamic like relation");
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Already liked");
                }
            } else if (Integer.valueOf(LIKE_STATUS_ACTIVE).equals(existingLike.getStatus())) {
                throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Already liked");
            } else if (Integer.valueOf(LIKE_STATUS_INACTIVE).equals(existingLike.getStatus())) {
                requireSingleRow(likeMapper.reactivateByUserAndTarget(
                        userId, dynamicId, DYNAMIC_LIKE_TARGET_TYPE),
                        "reactivate dynamic like relation");
            } else {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(),
                        "Invalid dynamic like relation status");
            }

            requireSingleRow(dynamicMapper.updateLikeCount(dynamicId),
                    "increment dynamic like count");
            evictDynamicDetailAfterCommit(dynamicId);

            final Long targetUserId = dynamic.getUserId();
            if (!userId.equals(targetUserId)) {
                sendNotificationAfterCommit(targetUserId, userId, 1, "Liked your dynamic", String.valueOf(dynamicId));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Operation too frequent, please retry later");
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void unlikeDynamic(Long userId, Long dynamicId) {
        String lockKey = DistributedLockService.LOCK_DYNAMIC_LIKE + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(),
                        ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }

            TuiLike existingLike = likeMapper.selectByUserAndTarget(
                    userId, dynamicId, DYNAMIC_LIKE_TARGET_TYPE);
            if (existingLike == null
                    || !Integer.valueOf(LIKE_STATUS_ACTIVE).equals(existingLike.getStatus())) {
                throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "Dynamic like not found");
            }

            requireSingleRow(likeMapper.deleteByUserAndTarget(
                    userId, dynamicId, DYNAMIC_LIKE_TARGET_TYPE),
                    "deactivate dynamic like relation");
            requireSingleRow(dynamicMapper.decrementLikeCount(dynamicId),
                    "decrement dynamic like count");
            evictDynamicDetailAfterCommit(dynamicId);
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(),
                    "Operation too frequent, please retry later");
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void commentDynamic(Long userId, Long dynamicId, String content) {
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
    @Transactional(timeout = 5)
    public void shareDynamic(Long userId, Long dynamicId) {
        TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        dynamicMapper.updateShareCount(dynamicId);
        evictDynamicDetailAfterCommit(dynamicId);

        final Long targetUserId = dynamic.getUserId();
        if (!userId.equals(targetUserId)) {
            sendNotificationAfterCommit(targetUserId, userId, 3, "Shared your dynamic", String.valueOf(dynamicId));
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void deleteDynamic(Long userId, Long dynamicId) {
        TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        if (!userId.equals(dynamic.getUserId())) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_OWNER.getCode(), ErrorCode.DYNAMIC_NOT_OWNER.getMessage());
        }
        dynamicMapper.deleteById(dynamicId);
        evictDynamicDetailAfterCommit(dynamicId);
    }

    @Override
    @Transactional(timeout = 5)
    public void follow(Long userId, Long followUserId) {
        if (userId.equals(followUserId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF.getCode(), ErrorCode.FOLLOW_SELF.getMessage());
        }

        String lockKey = DistributedLockService.LOCK_FOLLOW + userId + ":" + followUserId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiFollow existing = followMapper.selectByUserAndFollow(userId, followUserId);
            if (existing != null) {
                throw new BusinessException(ErrorCode.FOLLOW_ALREADY.getCode(), ErrorCode.FOLLOW_ALREADY.getMessage());
            }

            TuiFollow follow = new TuiFollow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(new Date());
            follow.setStatus(0);
            try {
                followMapper.insert(follow);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.FOLLOW_ALREADY.getCode(), ErrorCode.FOLLOW_ALREADY.getMessage());
            }

            sendNotificationAfterCommit(followUserId, userId, 4, "Followed you", String.valueOf(userId));
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "Operation too frequent, please retry later");
        }
    }

    @Override
    @Transactional(timeout = 5)
    public void unfollow(Long userId, Long followUserId) {
        TuiFollow existing = followMapper.selectByUserAndFollow(userId, followUserId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.FOLLOW_NOT_FOUND.getCode(), ErrorCode.FOLLOW_NOT_FOUND.getMessage());
        }
        followMapper.deleteByUserAndFollow(userId, followUserId);
    }

    @Override
    public Boolean isFollowing(Long userId, Long followUserId) {
        TuiFollow follow = followMapper.selectByUserAndFollow(userId, followUserId);
        return follow != null;
    }

    @Override
    public Integer countFollowers(Long userId) {
        Integer count = followMapper.countFollowers(userId);
        return count == null ? 0 : count;
    }

    @Override
    public Integer countFollowing(Long userId) {
        Integer count = followMapper.countFollowing(userId);
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

    private void requireSingleRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "Failed to " + operation);
        }
    }
}
