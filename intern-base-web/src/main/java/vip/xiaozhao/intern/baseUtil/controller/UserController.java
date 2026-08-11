package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "User")
@RestController
@RequestMapping("/api/user")
public class UserController extends BaseController {

    @Operation(summary = "Get current user info", description = "Get basic user profile data for the current logged-in user")
    @GetMapping("/info")
    public ResponseDO getUserInfo() {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", currentUserId);
        userInfo.put("username", "user" + currentUserId);
        userInfo.put("avatar", "https://example.com/avatar/" + currentUserId);
        userInfo.put("bio", "Default profile bio");
        userInfo.put("createTime", "2025-01-01 00:00:00");
        return success(userInfo);
    }

    @Operation(summary = "Update user info", description = "Update the current user profile")
    @PostMapping("/update")
    public ResponseDO updateUserInfo(@Parameter(description = "User profile payload", required = true) @RequestBody UpdateUserRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return success("updated");
    }

    @Operation(summary = "Get current user stats", description = "Get profile counters for the current logged-in user")
    @GetMapping("/stats")
    public ResponseDO getUserStats() {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("dynamicCount", 156);
        stats.put("followerCount", 1234);
        stats.put("followingCount", 567);
        stats.put("likeCount", 8901);
        return success(stats);
    }

    @Operation(summary = "Search users", description = "Search users by keyword")
    @PostMapping("/search")
    public ResponseDO searchUser(@Parameter(description = "Search request", required = true) @RequestBody SearchUserRequest request) {
        if (request.getKeyword() == null || request.getKeyword().isBlank()) {
            return fail("invalid request");
        }
        return success("searched");
    }

    @Operation(summary = "Get recommendations", description = "Get recommended users for the current user")
    @PostMapping("/recommend")
    public ResponseDO getRecommendUsers(@Parameter(description = "Recommendation request", required = true) @RequestBody RecommendRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        return success("recommended");
    }

    public static class UpdateUserRequest {
        private String username;
        private String avatar;
        private String bio;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getBio() { return bio; }
        public void setBio(String bio) { this.bio = bio; }
    }

    public static class SearchUserRequest {
        private String keyword;
        private Integer page;
        private Integer limit;

        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
    }

    public static class RecommendRequest {
        private Integer limit;

        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
    }
}
