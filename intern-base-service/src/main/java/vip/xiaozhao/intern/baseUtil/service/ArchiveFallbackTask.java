package vip.xiaozhao.intern.baseUtil.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import java.util.Calendar;
import java.util.List;

/**
 * 动态归档兜底定时任务
 * 作用：防止MQ延迟消息丢失导致动态永不归档
 * 原理：每天凌晨2点扫描7天前未归档的动态，批量归档
 * 兜底方案：定时任务 + MQ延迟消息双保险，即使MQ消息丢失也不影响归档
 */
@Component
public class ArchiveFallbackTask {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveFallbackTask.class);

    private static final String LOCK_KEY = "task:archive:lock";
    private static final int BATCH_SIZE = 500;
    private static final int LOCK_EXPIRE_SECONDS = 300; // 5分钟

    private final TuiDynamicMapper dynamicMapper;
    private final RedisUtil redisUtil;

    public ArchiveFallbackTask(TuiDynamicMapper dynamicMapper, RedisUtil redisUtil) {
        this.dynamicMapper = dynamicMapper;
        this.redisUtil = redisUtil;
    }

    /**
     * 每天凌晨2:00执行兜底归档
     * 使用Redis分布式锁防止多实例并发执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void archiveFallback() {
        // 尝试获取分布式锁，防止多实例同时执行
        Long locked = redisUtil.setnx(LOCK_KEY, String.valueOf(System.currentTimeMillis()));
        if (locked == null || locked != 1) {
            logger.info("Archive fallback task skipped: another instance is running");
            return;
        }
        redisUtil.expire(LOCK_KEY, LOCK_EXPIRE_SECONDS);

        try {
            // 计算7天前的时间
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -7);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            int totalArchived = 0;
            boolean hasMore = true;

            while (hasMore) {
                List<Long> ids = dynamicMapper.selectUnarchivedBefore(cal.getTime(), BATCH_SIZE);
                if (ids.isEmpty()) {
                    hasMore = false;
                } else {
                    for (Long id : ids) {
                        dynamicMapper.archiveById(id);
                    }
                    totalArchived += ids.size();
                    logger.info("Archive fallback batch: archived {} dynamics, total: {}", ids.size(), totalArchived);
                }
            }

            logger.info("Archive fallback task completed, total archived: {}", totalArchived);
        } catch (Exception e) {
            logger.error("Archive fallback task failed", e);
        } finally {
            redisUtil.delete(LOCK_KEY);
        }
    }
}