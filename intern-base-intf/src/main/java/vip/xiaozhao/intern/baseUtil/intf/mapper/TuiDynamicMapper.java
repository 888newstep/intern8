package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiDynamic;

import java.util.Date;
import java.util.List;

@Mapper
public interface TuiDynamicMapper {

    void insert(TuiDynamic dynamic);

    TuiDynamic selectById(Long id);

    List<TuiDynamic> selectFeedByCursor(@Param("userId") Long userId, @Param("cursor") Long cursor, @Param("limit") Integer limit);

    List<TuiDynamic> selectByUserId(@Param("userId") Long userId, @Param("cursor") Long cursor, @Param("limit") Integer limit);

    void updateLikeCount(@Param("id") Long id);

    void updateCommentCount(@Param("id") Long id);

    void updateShareCount(@Param("id") Long id);

    void deleteById(Long id);

    void archiveById(@Param("id") Long id);

    /**
     * 查询指定时间前未归档的动态ID列表（用于兜底定时任务）
     */
    List<Long> selectUnarchivedBefore(@Param("beforeTime") Date beforeTime, @Param("limit") Integer limit);
}