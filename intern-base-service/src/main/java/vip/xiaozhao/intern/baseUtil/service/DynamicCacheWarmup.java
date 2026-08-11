package vip.xiaozhao.intern.baseUtil.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiDynamicMapper;

import java.util.List;

/** Loads hot dynamic details after the application is ready. */
@Component
public class DynamicCacheWarmup {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCacheWarmup.class);
    private static final String CACHE_PREFIX = "dynamic:detail:";

    private final TuiDynamicMapper dynamicMapper;
    private final RedisCacheService redisCacheService;
    private final boolean enabled;
    private final int limit;
    public DynamicCacheWarmup(TuiDynamicMapper dynamicMapper,
                              RedisCacheService redisCacheService,
                              @Value("${cache.warmup.enabled:false}") boolean enabled,
                              @Value("${cache.warmup.dynamic-limit:100}") int limit) {
        this.dynamicMapper = dynamicMapper;
        this.redisCacheService = redisCacheService;
        this.enabled = enabled;
        this.limit = Math.max(1, Math.min(limit, 1000));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!enabled) {
            logger.info("Dynamic cache warmup is disabled");
            return;
        }

        try {
            List<Long> dynamicIds = dynamicMapper.selectHotDynamicIds(limit);
            for (Long dynamicId : dynamicIds) {
                if (dynamicId == null) {
                    continue;
                }
                redisCacheService.get(CACHE_PREFIX + dynamicId, TuiDynamic.class,
                        () -> dynamicMapper.selectById(dynamicId));
            }
            logger.info("Dynamic cache warmup completed, loaded {} entries", dynamicIds.size());
        } catch (Exception e) {
            logger.warn("Dynamic cache warmup failed; application remains available", e);
        }
    }

}
