package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "通知管理")
@RestController
@RequestMapping("/api/notification")
@Validated
public class NotificationController extends BaseController {

    private final RedisUtil redisUtil;
    private final NotificationService notificationService;
    private final TuiNotificationMapper notificationMapper;

    public NotificationController(RedisUtil redisUtil, NotificationService notificationService,
                                  TuiNotificationMapper notificationMapper) {
        this.redisUtil = redisUtil;
        this.notificationService = notificationService;
        this.notificationMapper = notificationMapper;
    }

    private static final String UNREAD_COUNT_PREFIX = "notification:unread:count:";
    private static final String LAST_REFRESH_PREFIX = "notification:last:refresh:";

    @Operation(summary = "获取未读通知数量", description = "获取用户的未读通知数量和最后刷新时间戳，Redis优先，DB兜底")
    @PostMapping("/unread/count")
    public ResponseDO getUnreadCount(@Parameter(description = "请求参数", required = true) @Valid @RequestBody UnreadCountRequest request) {
        String key = UNREAD_COUNT_PREFIX + request.getUserId();
        Long count = redisUtil.getCount(key);

        if (count == 0) {
            Integer dbCount = notificationMapper.countUnread(request.getUserId());
            count = dbCount == null ? 0L : dbCount.longValue();
        }

        String refreshKey = LAST_REFRESH_PREFIX + request.getUserId();
        String lastRefresh = redisUtil.get(refreshKey);

        Map<String, Object> result = new HashMap<>();
        result.put("unreadCount", count);
        result.put("lastRefresh", lastRefresh);

        return success(result);
    }

    @Operation(summary = "标记所有通知为已读", description = "标记用户的所有通知为已读，清空Redis计数")
    @PostMapping("/read/all")
    public ResponseDO markAllAsRead(@Parameter(description = "请求参数", required = true) @Valid @RequestBody MarkReadRequest request) {
        String key = UNREAD_COUNT_PREFIX + request.getUserId();
        redisUtil.delete(key);

        String refreshKey = LAST_REFRESH_PREFIX + request.getUserId();
        redisUtil.set(refreshKey, String.valueOf(System.currentTimeMillis()));

        notificationMapper.updateIsRead(request.getUserId());

        return success("标记成功");
    }

    @Operation(summary = "获取通知列表", description = "获取用户的通知列表，采用游标分页")
    @PostMapping("/list")
    public ResponseDO getNotificationList(@Parameter(description = "通知列表请求", required = true) @Valid @RequestBody NotificationListRequest request) {
        Long cursor = request.getCursor() == null ? Long.MAX_VALUE : request.getCursor();
        Integer limit = request.getLimit() == null ? 20 : request.getLimit();

        List<TuiNotification> notifications = notificationMapper.selectByUserId(request.getUserId(), cursor, limit);

        NotificationListResponse response = new NotificationListResponse(
                notifications,
                notifications.isEmpty() ? 0L : notifications.get(notifications.size() - 1).getId(),
                notifications.size() >= limit
        );

        return success(response);
    }

    @Operation(summary = "删除通知", description = "删除指定通知")
    @PostMapping("/delete")
    public ResponseDO deleteNotification(@Parameter(description = "删除请求", required = true) @Valid @RequestBody DeleteNotificationRequest request) {
        TuiNotification notification = notificationMapper.selectById(request.getNotificationId());
        if (notification == null) {
            return fail("通知不存在");
        }
        notificationMapper.deleteById(request.getNotificationId());
        return success("删除成功");
    }

    @Operation(summary = "获取通知详情", description = "获取单条通知的详细信息")
    @GetMapping("/detail/{id}")
    public ResponseDO getNotificationDetail(@Parameter(description = "通知ID", required = true) @PathVariable Long id) {
        TuiNotification notification = notificationMapper.selectById(id);
        if (notification == null) {
            return fail("通知不存在");
        }
        return success(notification);
    }

    @Operation(summary = "发送系统通知", description = "发送系统通知给指定用户")
    @PostMapping("/system/send")
    public ResponseDO sendSystemNotification(@Parameter(description = "系统通知请求", required = true) @Valid @RequestBody SystemNotificationRequest request) {
        notificationService.sendNotification(
                request.getUserId(),
                0L,
                5,
                request.getContent(),
                "SYSTEM"
        );

        return success("发送成功");
    }
}