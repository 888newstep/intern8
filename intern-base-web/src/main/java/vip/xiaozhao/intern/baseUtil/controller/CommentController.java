package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.service.CommentService;
import vip.xiaozhao.intern.baseUtil.service.ApiIdempotencyService;
import vip.xiaozhao.intern.baseUtil.service.RateLimiterService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import vip.xiaozhao.intern.baseUtil.intf.validation.XssSafe;
import java.util.List;

@Tag(name = "评论管理")
@RestController
@RequestMapping("/api/comment")
@Validated
public class CommentController extends BaseController {

    private final CommentService commentService;
    private final RateLimiterService rateLimiterService;
    private final ApiIdempotencyService apiIdempotencyService;

    public CommentController(CommentService commentService,
                             RateLimiterService rateLimiterService,
                             ApiIdempotencyService apiIdempotencyService) {
        this.commentService = commentService;
        this.rateLimiterService = rateLimiterService;
        this.apiIdempotencyService = apiIdempotencyService;
    }

    @Operation(summary = "获取评论列表", description = "获取指定动态的评论列表，采用游标分页")
    @PostMapping("/list")
    public ResponseDO getCommentList(@Valid @RequestBody CommentListRequest request) {
        List<TuiComment> comments = commentService.getCommentList(
                request.getDynamicId(), request.getCursor(), request.getLimit());

        long nextCursor = comments.isEmpty() ? 0L : comments.get(comments.size() - 1).getId();
        boolean hasMore = comments.size() >= (request.getLimit() == null ? 20 : request.getLimit());

        return success(new CommentListResponse(comments, nextCursor, hasMore));
    }

    @Operation(summary = "添加评论", description = "对动态添加评论，每分钟限20次")
    @PostMapping("/add")
    public ResponseDO addComment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddCommentRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/comment/add",
                idempotencyKey, request, () -> {
                    if (!rateLimiterService.tryAcquireComment(currentUserId)) {
                        return fail(429, "操作太频繁，每分钟最多评论20次");
                    }
                    commentService.addComment(currentUserId, request.getDynamicId(),
                            request.getContent(), request.getParentId(), request.getReplyUserId());
                    return success("评论成功");
                });
    }

    @Operation(summary = "评论点赞", description = "对评论进行点赞")
    @PostMapping("/like")
    public ResponseDO likeComment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LikeCommentRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return apiIdempotencyService.execute(currentUserId, "POST:/api/comment/like",
                idempotencyKey, request, () -> {
                    commentService.likeComment(currentUserId, request.getCommentId());
                    return success("点赞成功");
                });
    }

    @Operation(summary = "删除评论", description = "删除自己的评论")
    @PostMapping("/delete")
    public ResponseDO deleteComment(@Valid @RequestBody DeleteCommentRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        commentService.deleteComment(currentUserId, request.getCommentId());
        return success("删除成功");
    }

    // ==================== Request DTOs ====================

    public static class CommentListRequest {
        @NotNull
        @Positive
        private Long dynamicId;
        @Positive
        private Long cursor;
        @Min(1)
        @Max(100)
        private Integer limit;

        public Long getDynamicId() { return dynamicId; }
        public void setDynamicId(Long dynamicId) { this.dynamicId = dynamicId; }
        public Long getCursor() { return cursor; }
        public void setCursor(Long cursor) { this.cursor = cursor; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
    }

    public static class CommentListResponse {
        private List<TuiComment> comments;
        private Long cursor;
        private Boolean hasMore;

        public CommentListResponse(List<TuiComment> comments, Long cursor, Boolean hasMore) {
            this.comments = comments;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }

        public List<TuiComment> getComments() { return comments; }
        public Long getCursor() { return cursor; }
        public Boolean getHasMore() { return hasMore; }
    }

    public static class AddCommentRequest {
        @NotNull
        @Positive
        private Long dynamicId;
        @NotNull
        @NotBlank
        @XssSafe
        @jakarta.validation.constraints.Size(max = 500)
        private String content;
        @Positive
        private Long parentId;
        @Positive
        private Long replyUserId;

        public Long getDynamicId() { return dynamicId; }
        public void setDynamicId(Long dynamicId) { this.dynamicId = dynamicId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public Long getReplyUserId() { return replyUserId; }
        public void setReplyUserId(Long replyUserId) { this.replyUserId = replyUserId; }
    }

    public static class LikeCommentRequest {
        @NotNull
        @Positive
        private Long commentId;

        public Long getCommentId() { return commentId; }
        public void setCommentId(Long commentId) { this.commentId = commentId; }
    }

    public static class DeleteCommentRequest {
        @NotNull
        @Positive
        private Long commentId;

        public Long getCommentId() { return commentId; }
        public void setCommentId(Long commentId) { this.commentId = commentId; }
    }
}
