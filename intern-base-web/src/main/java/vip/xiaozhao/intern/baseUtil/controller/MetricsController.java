package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.service.CircuitBreakerService;
import vip.xiaozhao.intern.baseUtil.service.RedisCacheService;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Monitoring Metrics")
@RestController
@RequestMapping("/api/metrics")
public class MetricsController extends BaseController {

    private final RedisCacheService redisCacheService;
    private final CircuitBreakerService circuitBreakerService;

    public MetricsController(RedisCacheService redisCacheService,
                             CircuitBreakerService circuitBreakerService) {
        this.redisCacheService = redisCacheService;
        this.circuitBreakerService = circuitBreakerService;
    }

    @Operation(summary = "Cache metrics")
    @GetMapping("/cache")
    public ResponseDO getCacheMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("localHitCount", redisCacheService.getLocalHitCount());
        metrics.put("redisHitCount", redisCacheService.getRedisHitCount());
        metrics.put("hitCount", redisCacheService.getCacheHitCount());
        metrics.put("missCount", redisCacheService.getCacheMissCount());
        metrics.put("dbLoadCount", redisCacheService.getDbLoadCount());
        metrics.put("hitRate", redisCacheService.getCacheHitRate());
        metrics.put("localCacheStats", redisCacheService.getLocalCacheStats());
        return success(metrics);
    }

    @Operation(summary = "Circuit breaker status")
    @GetMapping("/circuit-breaker")
    public ResponseDO getCircuitBreakerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("redis", circuitBreakerService.getRedisState().name());
        status.put("rabbitMq", circuitBreakerService.getMqState().name());
        status.put("cos", circuitBreakerService.getCosState().name());
        return success(status);
    }
}
