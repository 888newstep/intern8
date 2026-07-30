package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiFollow;

import java.util.List;

@Mapper
public interface TuiFollowMapper {

    void insert(TuiFollow follow);

    void deleteByUserAndFollow(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    TuiFollow selectByUserAndFollow(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    List<Long> selectFollowUserIds(@Param("userId") Long userId);

    Integer countFollowers(@Param("userId") Long userId);

    Integer countFollowing(@Param("userId") Long userId);
}