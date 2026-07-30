package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;
import vip.xiaozhao.intern.baseUtil.intf.service.CommentService;
import vip.xiaozhao.intern.baseUtil.service.RateLimiterService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Tag(name = "评论管理")
@RestController
@RequestMapping("/api/comment")
@Validated
public class CommentController extends BaseController {

    private final CommentService commentService;
    private final RateLimiterService rateLimiterService;

    public CommentController(CommentService commentService, RateLimiterService rateLimiterService) {
        this.commentService = commentService;
        this.rateLimiterService = rateLimiterService;
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
    public ResponseDO addComment(@Valid @RequestBody AddCommentRequest request) {
        if (!rateLimiterService.tryAcquireComment(request.getUserId())) {
            return fail(429, "操作太频繁，每分钟最多评论20次");
        }
        commentService.addComment(request.getUserId(), request.getDynamicId(),
                request.getContent(), request.getParentId(), request.getReplyUserId());
        return success("评论成功");
    }

    @Operation(summary = "评论点赞", description = "对评论进行点赞")
    @PostMapping("/like")
    public ResponseDO likeComment(@Valid @RequestBody LikeCommentRequest request) {
        commentService.likeComment(request.getUserId(), request.getCommentId());
        return success("点赞成功");
    }

    @Operation(summary = "删除评论", description = "删除自己的评论")
    @PostMapping("/delete")
    public ResponseDO deleteComment(@Valid @RequestBody DeleteCommentRequest request) {
        commentService.deleteComment(request.getUserId(), request.getCommentId());
        return success("删除成功");
    }

    // ==================== Request DTOs ====================

    public static class CommentListRequest {
        @NotNull
        private Long dynamicId;
        private Long cursor;
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
        private Long userId;
        @NotNull
        private Long dynamicId;
        @NotNull
        private String content;
        private Long parentId;
        private Long replyUserId;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
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
        private Long userId;
        @NotNull
        private Long commentId;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getCommentId() { return commentId; }
        public void setCommentId(Long commentId) { this.commentId = commentId; }
    }

    public static class DeleteCommentRequest {
        @NotNull
        private Long userId;
        @NotNull
        private Long commentId;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getCommentId() { return commentId; }
        public void setCommentId(Long commentId) { this.commentId = commentId; }
    }
}