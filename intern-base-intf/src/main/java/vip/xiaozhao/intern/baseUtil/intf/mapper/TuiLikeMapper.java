package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiLike;

@Mapper
public interface TuiLikeMapper {
    int insert(TuiLike like);
    TuiLike selectByUserAndTarget(@Param("userId") Long userId, @Param("targetId") Long targetId, @Param("targetType") Integer targetType);
    int reactivateByUserAndTarget(@Param("userId") Long userId, @Param("targetId") Long targetId, @Param("targetType") Integer targetType);
    int deleteByUserAndTarget(@Param("userId") Long userId, @Param("targetId") Long targetId, @Param("targetType") Integer targetType);
}
