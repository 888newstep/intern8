package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiComment;

import java.util.List;

public interface TuiCommentMapper {

    int insert(TuiComment comment);

    TuiComment selectById(@Param("id") Long id);

    List<TuiComment> selectByDynamicId(@Param("dynamicId") Long dynamicId,
                                       @Param("cursor") Long cursor,
                                       @Param("limit") Integer limit);

    int updateLikeCount(@Param("id") Long id);

    int decrementLikeCount(@Param("id") Long id);

    int deleteById(@Param("id") Long id);

    int countByDynamicId(@Param("dynamicId") Long dynamicId);
}
