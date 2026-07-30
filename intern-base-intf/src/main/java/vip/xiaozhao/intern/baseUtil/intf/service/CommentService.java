package vip.xiaozhao.intern.baseUtil.intf.service;

import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;

import java.util.List;

public interface CommentService {

    void addComment(Long userId, Long dynamicId, String content, Long parentId, Long replyUserId);

    List<TuiComment> getCommentList(Long dynamicId, Long cursor, Integer limit);

    void likeComment(Long userId, Long commentId);

    void deleteComment(Long userId, Long commentId);

    int countByDynamicId(Long dynamicId);
}