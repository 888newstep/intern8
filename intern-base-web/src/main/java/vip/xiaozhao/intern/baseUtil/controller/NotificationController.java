package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.dto.request.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.response.NotificationListResponse;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiNotification;
import vip.xiaozhao.intern.baseUtil.intf.mapper.TuiNotificationMapper;
import vip.xiaozhao.intern.baseUtil.intf.service.NotificationService;
import vip.xiaozhao.intern.baseUtil.utils.RedisUtil;

import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "????")
@RestController
@RequestMapping("/api/notification")
@Validated
public class NotificationController extends BaseController {

    private final RedisUtil redisUtil;
    private final NotificationService notificationService;
    private final TuiNotificationMapper notificationMapper;
    private final Set<Long> adminIds;

    public NotificationController(RedisUtil redisUtil, NotificationService notificationService,
                                  TuiNotificationMapper notificationMapper,
                                  @Value("${system.notification.admin-ids:}") String adminIdsConfig) {
        this.redisUtil = redisUtil;
        this.notificationService = notificationService;
        this.notificationMapper = notificationMapper;
        this.adminIds = Arrays.stream(adminIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    private static final String UNREAD_COUNT_PREFIX = "notification:unread:count:";
    private static final String LAST_REFRESH_PREFIX = "notification:last:refresh:";

    @Operation(summary = "????????", description = "????????????????????Redis???DB??")
    @PostMapping("/unread/count")
    public ResponseDO getUnreadCount(@Parameter(description = "????", required = true) @Valid @RequestBody UnreadCountRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }

        String key = UNREAD_COUNT_PREFIX + currentUserId;
        Long count = redisUtil.getCount(key);

        if (count == 0) {
            Integer dbCount = notificationMapper.countUnread(currentUserId);
            count = dbCount == null ? 0L : dbCount.longValue();
        }

        String refreshKey = LAST_REFRESH_PREFIX + currentUserId;
        String lastRefresh = redisUtil.get(refreshKey);

        Map<String, Object> result = new HashMap<>();
        result.put("unreadCount", count);
        result.put("lastRefresh", lastRefresh);

        return success(result);
    }

    @Operation(summary = "?????????", description = "???????????????Redis??")
    @PostMapping("/read/all")
    public ResponseDO markAllAsRead(@Parameter(description = "????", required = true) @Valid @RequestBody MarkReadRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }

        String key = UNREAD_COUNT_PREFIX + currentUserId;
        redisUtil.delete(key);

        String refreshKey = LAST_REFRESH_PREFIX + currentUserId;
        redisUtil.set(refreshKey, String.valueOf(System.currentTimeMillis()));

        notificationMapper.updateIsRead(currentUserId);

        return success("????");
    }

    @Operation(summary = "??????", description = "????????????????")
    @PostMapping("/list")
    public ResponseDO getNotificationList(@Parameter(description = "??????", required = true) @Valid @RequestBody NotificationListRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }

        Long cursor = request.getCursor() == null ? Long.MAX_VALUE : request.getCursor();
        Integer limit = request.getLimit() == null ? 20 : Math.max(1, Math.min(request.getLimit(), 100));

        List<TuiNotification> notifications = notificationMapper.selectByUserId(currentUserId, cursor, limit);

        NotificationListResponse response = new NotificationListResponse(
                notifications,
                notifications.isEmpty() ? 0L : notifications.get(notifications.size() - 1).getId(),
                notifications.size() >= limit
        );

        return success(response);
    }

    @Operation(summary = "????", description = "??????")
    @PostMapping("/delete")
    public ResponseDO deleteNotification(@Parameter(description = "????", required = true) @Valid @RequestBody DeleteNotificationRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }

        TuiNotification notification = notificationMapper.selectById(request.getNotificationId());
        if (notification == null) {
            return fail("?????");
        }
        if (!currentUserId.equals(notification.getUserId())) {
            return forbidden();
        }
        notificationMapper.deleteById(request.getNotificationId());
        return success("????");
    }

    @Operation(summary = "??????", description = "???????????")
    @GetMapping("/detail/{id}")
    public ResponseDO getNotificationDetail(@Parameter(description = "??ID", required = true) @PathVariable Long id) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }

        TuiNotification notification = notificationMapper.selectById(id);
        if (notification == null) {
            return fail("?????");
        }
        if (!currentUserId.equals(notification.getUserId())) {
            return forbidden();
        }
        return success(notification);
    }

    @Operation(summary = "??????", description = "???????????????????")
    @PostMapping("/system/send")
    public ResponseDO sendSystemNotification(@Parameter(description = "??????", required = true) @Valid @RequestBody SystemNotificationRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        
        if (!adminIds.contains(currentUserId)) {
            return forbidden();
        }

        notificationService.sendNotification(
                request.getTargetUserId(),
                0L,
                5,
                request.getContent(),
                "SYSTEM"
        );

        return success("????");
    }
}
