package vip.xiaozhao.intern.baseUtil.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.service.CommentService;
import vip.xiaozhao.intern.baseUtil.service.ApiIdempotencyService;
import vip.xiaozhao.intern.baseUtil.service.RateLimiterService;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentControllerRateLimitTest {

    private static final Long USER_ID = 100L;
    private static final Long COMMENT_ID = 200L;

    @Mock
    private CommentService commentService;
    @Mock
    private RateLimiterService rateLimiterService;
    @Mock
    private ApiIdempotencyService apiIdempotencyService;

    private CommentController controller;
    private CommentController.LikeCommentRequest request;

    @BeforeEach
    void setUp() {
        controller = new CommentController(
                commentService, rateLimiterService, apiIdempotencyService);
        request = new CommentController.LikeCommentRequest();
        request.setCommentId(COMMENT_ID);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(apiIdempotencyService.execute(
                anyLong(), anyString(), nullable(String.class), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<ResponseDO> operation = invocation.getArgument(4);
                    return operation.get();
                });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void likeComment_ShouldRejectWhenLikeRateLimitIsExceeded() {
        when(rateLimiterService.tryAcquireLike(USER_ID)).thenReturn(false);

        ResponseDO response = controller.likeComment(null, request);

        assertFalse(response.isSuccess());
        assertEquals(429, response.getErrorCode());
        verify(commentService, never()).likeComment(anyLong(), anyLong());
    }

    @Test
    void likeComment_ShouldProceedWhenLikeRateLimitAllowsRequest() {
        when(rateLimiterService.tryAcquireLike(USER_ID)).thenReturn(true);

        ResponseDO response = controller.likeComment(null, request);

        assertTrue(response.isSuccess());
        verify(commentService).likeComment(USER_ID, COMMENT_ID);
    }

    @Test
    void unlikeComment_ShouldRejectWhenLikeRateLimitIsExceeded() {
        when(rateLimiterService.tryAcquireLike(USER_ID)).thenReturn(false);

        ResponseDO response = controller.unlikeComment(null, request);

        assertFalse(response.isSuccess());
        assertEquals(429, response.getErrorCode());
        verify(commentService, never()).unlikeComment(anyLong(), anyLong());
    }

    @Test
    void unlikeComment_ShouldProceedWhenLikeRateLimitAllowsRequest() {
        when(rateLimiterService.tryAcquireLike(USER_ID)).thenReturn(true);

        ResponseDO response = controller.unlikeComment(null, request);

        assertTrue(response.isSuccess());
        verify(commentService).unlikeComment(USER_ID, COMMENT_ID);
    }
}
