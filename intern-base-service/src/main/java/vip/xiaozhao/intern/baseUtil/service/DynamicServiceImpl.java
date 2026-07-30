package vip.xiaozhao.intern.baseUtil.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiFollow;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiFollowMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.DynamicService;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.SnowflakeIdGenerator;

import java.util.Date;
import java.util.List;

@Service
public class DynamicServiceImpl implements DynamicService {

    private final TuiDynamicMapper dynamicMapper;
    private final TuiFollowMapper followMapper;
    private final NotificationService notificationService;
    private final DistributedLockService lockService;
    private final RabbitMQSender rabbitMQSender;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public DynamicServiceImpl(TuiDynamicMapper dynamicMapper, TuiFollowMapper followMapper,
                              NotificationService notificationService,
                              DistributedLockService lockService,
                              RabbitMQSender rabbitMQSender,
                              SnowflakeIdGenerator snowflakeIdGenerator) {
        this.dynamicMapper = dynamicMapper;
        this.followMapper = followMapper;
        this.notificationService = notificationService;
        this.lockService = lockService;
        this.rabbitMQSender = rabbitMQSender;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Override
    @Transactional
    public void saveDynamic(Long userId, String content, String images) {
        // 1. 分布式锁防止同一用户重复提交
        String lockKey = DistributedLockService.LOCK_DYNAMIC_PUBLISH + userId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            // 2. 雪花算法生成全局唯一ID，替代自增ID
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

            // 3. 事务提交后发送延迟归档消息（7天后自动归档）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rabbitMQSender.sendArchiveMessage(dynamicId);
                }
            });
        });

        // 锁冲突 ≠ 操作频繁：锁获取失败说明系统负载高，不是用户刷屏
        if (!locked) {
            throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED.getCode(),
                    ErrorCode.LOCK_ACQUIRE_FAILED.getMessage());
        }
    }

    @Override
    @Cacheable(value = "dynamic", key = "#id", unless = "#result == null")
    public TuiDynamic getDynamicById(Long id) {
        TuiDynamic dynamic = dynamicMapper.selectById(id);
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        return dynamic;
    }

    @Override
    public List<TuiDynamic> getFeed(Long userId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.min(limit, 100);
        return dynamicMapper.selectFeedByCursor(userId, actualCursor, actualLimit);
    }

    @Override
    public List<TuiDynamic> getUserDynamics(Long userId, Long cursor, Integer limit) {
        Long actualCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Integer actualLimit = limit == null ? 20 : Math.min(limit, 100);
        return dynamicMapper.selectByUserId(userId, actualCursor, actualLimit);
    }

    @Override
    @Transactional
    public void likeDynamic(Long userId, Long dynamicId) {
        // 分布式锁：防止同一用户对同一动态重复点赞
        String lockKey = DistributedLockService.LOCK_DYNAMIC_LIKE + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }
            dynamicMapper.updateLikeCount(dynamicId);

            final Long targetUserId = dynamic.getUserId();
            if (!userId.equals(targetUserId)) {
                sendNotificationAfterCommit(targetUserId, userId, 1, "点赞了你的动态", String.valueOf(dynamicId));
            }
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "操作太频繁，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void commentDynamic(Long userId, Long dynamicId, String content) {
        // 分布式锁：防止同一用户对同一动态重复评论
        String lockKey = DistributedLockService.LOCK_DYNAMIC_COMMENT + userId + ":" + dynamicId;
        boolean locked = lockService.executeWithLockVoid(lockKey, () -> {
            TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
            if (dynamic == null) {
                throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
            }
            dynamicMapper.updateCommentCount(dynamicId);

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
    @Transactional
    public void shareDynamic(Long userId, Long dynamicId) {
        TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        dynamicMapper.updateShareCount(dynamicId);

        final Long targetUserId = dynamic.getUserId();
        if (!userId.equals(targetUserId)) {
            sendNotificationAfterCommit(targetUserId, userId, 3, "分享了你的动态", String.valueOf(dynamicId));
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = "dynamic", key = "#dynamicId")
    public void deleteDynamic(Long userId, Long dynamicId) {
        TuiDynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_FOUND.getCode(), ErrorCode.DYNAMIC_NOT_FOUND.getMessage());
        }
        if (!userId.equals(dynamic.getUserId())) {
            throw new BusinessException(ErrorCode.DYNAMIC_NOT_OWNER.getCode(), ErrorCode.DYNAMIC_NOT_OWNER.getMessage());
        }
        dynamicMapper.deleteById(dynamicId);
    }

    @Override
    @Transactional
    public void follow(Long userId, Long followUserId) {
        if (userId.equals(followUserId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF.getCode(), ErrorCode.FOLLOW_SELF.getMessage());
        }

        // 分布式锁：防止同一用户重复关注
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
            followMapper.insert(follow);

            sendNotificationAfterCommit(followUserId, userId, 4, "关注了你", String.valueOf(userId));
        });

        if (!locked) {
            throw new BusinessException(ErrorCode.TOO_FREQUENT.getCode(), "操作太频繁，请稍后重试");
        }
    }

    @Override
    @Transactional
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