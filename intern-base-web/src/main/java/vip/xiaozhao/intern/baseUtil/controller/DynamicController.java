package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.dto.request.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.response.FeedResponse;
import vip.xiaozhao.intern.baseUtil.intf.dto.response.FollowCountResponse;
import vip.xiaozhao.intern.baseUtil.intf.dto.response.FollowStatusResponse;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;
import vip.xiaozhao.intern.baseUtil.intf.service.DynamicService;
import vip.xiaozhao.intern.baseUtil.service.ApiIdempotencyService;
import vip.xiaozhao.intern.baseUtil.service.RateLimiterService;

import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "动态管理")
@RestController
@RequestMapping("/api/dynamic")
@Validated
public class DynamicController extends BaseController {

    private final DynamicService dynamicService;
    private final RateLimiterService rateLimiterService;
    private final ApiIdempotencyService apiIdempotencyService;

    public DynamicController(DynamicService dynamicService,
                             RateLimiterService rateLimiterService,
                             ApiIdempotencyService apiIdempotencyService) {
        this.dynamicService = dynamicService;
        this.rateLimiterService = rateLimiterService;
        this.apiIdempotencyService = apiIdempotencyService;
    }

    @Operation(summary = "发布动态", description = "发布用户动态，支持文本和图片，每分钟限5条，分布式锁防止重复提交")
    @PostMapping("/publish")
    public ResponseDO publishDynamic(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "动态信息", required = true) @Valid @RequestBody DynamicPublishRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/dynamic/publish",
                idempotencyKey, request, () -> {
                    // 幂等占有成功后再消耗限流额度，重复请求不会重复扣配额。
                    if (!rateLimiterService.tryAcquirePublish(currentUserId)) {
                        return fail(429, "发布太频繁，每分钟最多发布5条动态");
                    }
                    dynamicService.saveDynamic(currentUserId, request.getContent(), request.getImages());
                    return success("发布成功");
                });
    }

    @Operation(summary = "获取推友圈信息流", description = "获取用户关注的推友动态，采用游标分页")
    @PostMapping("/feed")
    public ResponseDO getFeed(@Parameter(description = "信息流请求", required = true) @Valid @RequestBody FeedRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        Long cursor = request.getCursor() == null ? Long.MAX_VALUE : request.getCursor();
        Integer limit = request.getLimit() == null ? 20 : request.getLimit();

        List<TuiDynamic> dynamics = dynamicService.getFeed(currentUserId, cursor, limit);

        FeedResponse response = new FeedResponse(
                dynamics,
                dynamics.isEmpty() ? 0L : dynamics.get(dynamics.size() - 1).getId(),
                dynamics.size() >= limit
        );

        return success(response);
    }

    @Operation(summary = "获取用户动态列表", description = "获取指定用户的动态列表，采用游标分页")
    @PostMapping("/user/list")
    public ResponseDO getUserDynamics(@Parameter(description = "用户动态请求", required = true) @Valid @RequestBody UserDynamicRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        Long cursor = request.getCursor() == null ? Long.MAX_VALUE : request.getCursor();
        Integer limit = request.getLimit() == null ? 20 : request.getLimit();

        List<TuiDynamic> dynamics = dynamicService.getUserDynamics(currentUserId, cursor, limit);

        FeedResponse response = new FeedResponse(
                dynamics,
                dynamics.isEmpty() ? 0L : dynamics.get(dynamics.size() - 1).getId(),
                dynamics.size() >= limit
        );

        return success(response);
    }

    @Operation(summary = "获取动态详情", description = "获取单条动态的详细信息")
    @GetMapping("/detail/{id}")
    public ResponseDO getDynamicDetail(@Parameter(description = "动态ID", required = true) @PathVariable Long id) {
        TuiDynamic dynamic = dynamicService.getDynamicById(id);
        return success(dynamic);
    }

    @Operation(summary = "点赞动态", description = "对动态进行点赞（每分钟限30次）")
    @PostMapping("/like")
    public ResponseDO likeDynamic(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "点赞请求", required = true) @Valid @RequestBody LikeRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/dynamic/like",
                idempotencyKey, request, () -> {
                    if (!rateLimiterService.tryAcquireLike(currentUserId)) {
                        return fail(429, "操作太频繁，每分钟最多点赞30次");
                    }
                    dynamicService.likeDynamic(currentUserId, request.getDynamicId());
                    return success("点赞成功");
                });
    }

    @Operation(summary = "取消动态点赞", description = "取消对动态的点赞（每分钟点赞操作限30次）")
    @PostMapping("/unlike")
    public ResponseDO unlikeDynamic(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "取消点赞请求", required = true) @Valid @RequestBody LikeRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/dynamic/unlike",
                idempotencyKey, request, () -> {
                    if (!rateLimiterService.tryAcquireLike(currentUserId)) {
                        return fail(429, "操作太频繁，每分钟最多执行30次点赞操作");
                    }
                    dynamicService.unlikeDynamic(currentUserId, request.getDynamicId());
                    return success("取消点赞成功");
                });
    }

    @Operation(summary = "评论动态", description = "对动态进行评论（每分钟限20次）")
    @PostMapping("/comment")
    public ResponseDO commentDynamic(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "评论请求", required = true) @Valid @RequestBody CommentRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/dynamic/comment",
                idempotencyKey, request, () -> {
                    if (!rateLimiterService.tryAcquireComment(currentUserId)) {
                        return fail(429, "操作太频繁，每分钟最多评论20次");
                    }
                    dynamicService.commentDynamic(currentUserId, request.getDynamicId(), request.getContent());
                    return success("评论成功");
                });
    }

    @Operation(summary = "分享动态", description = "分享动态")
    @PostMapping("/share")
    public ResponseDO shareDynamic(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "分享请求", required = true) @Valid @RequestBody ShareRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/dynamic/share",
                idempotencyKey, request, () -> {
                    dynamicService.shareDynamic(currentUserId, request.getDynamicId());
                    return success("分享成功");
                });
    }

    @Operation(summary = "删除动态", description = "删除自己的动态")
    @PostMapping("/delete")
    public ResponseDO deleteDynamic(@Parameter(description = "删除请求", required = true) @Valid @RequestBody DeleteDynamicRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        dynamicService.deleteDynamic(currentUserId, request.getDynamicId());
        return success("删除成功");
    }

    @Operation(summary = "关注用户", description = "关注指定用户")
    @PostMapping("/follow")
    public ResponseDO follow(@Parameter(description = "关注请求", required = true) @Valid @RequestBody FollowRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        dynamicService.follow(currentUserId, request.getFollowUserId());
        return success("关注成功");
    }

    @Operation(summary = "取消关注", description = "取消关注指定用户")
    @PostMapping("/unfollow")
    public ResponseDO unfollow(@Parameter(description = "取消关注请求", required = true) @Valid @RequestBody FollowRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        dynamicService.unfollow(currentUserId, request.getFollowUserId());
        return success("取消关注成功");
    }

    @Operation(summary = "检查关注状态", description = "检查是否已关注指定用户")
    @PostMapping("/follow/check")
    public ResponseDO checkFollow(@Parameter(description = "关注状态请求", required = true) @Valid @RequestBody FollowRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        Boolean isFollowing = dynamicService.isFollowing(currentUserId, request.getFollowUserId());
        return success(new FollowStatusResponse(isFollowing));
    }

    @Operation(summary = "获取关注数", description = "获取用户的粉丝数和关注数")
    @GetMapping("/follow/count/{userId}")
    public ResponseDO getFollowCount(@Parameter(description = "用户ID", required = true) @PathVariable Long userId) {
        Integer followers = dynamicService.countFollowers(userId);
        Integer following = dynamicService.countFollowing(userId);
        return success(new FollowCountResponse(followers, following));
    }
}
