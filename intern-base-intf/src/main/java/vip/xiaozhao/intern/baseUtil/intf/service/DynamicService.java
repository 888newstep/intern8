package vip.xiaozhao.intern.baseUtil.intf.service;

import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;

import java.util.List;

public interface DynamicService {

    void saveDynamic(Long userId, String content, String images);

    TuiDynamic getDynamicById(Long id);

    List<TuiDynamic> getFeed(Long userId, Long cursor, Integer limit);

    List<TuiDynamic> getUserDynamics(Long userId, Long cursor, Integer limit);

    void likeDynamic(Long userId, Long dynamicId);

    void commentDynamic(Long userId, Long dynamicId, String content);

    void shareDynamic(Long userId, Long dynamicId);

    void deleteDynamic(Long userId, Long dynamicId);

    void follow(Long userId, Long followUserId);

    void unfollow(Long userId, Long followUserId);

    Boolean isFollowing(Long userId, Long followUserId);

    Integer countFollowers(Long userId);

    Integer countFollowing(Long userId);
}