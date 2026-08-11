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

    /**
     * 先按游标和关注关系筛选动态 ID，减少排序阶段需要搬运的宽行数据。
     */
    List<Long> selectFeedDynamicIds(@Param("userId") Long userId,
                                    @Param("cursor") Long cursor,
                                    @Param("limit") Integer limit);

    /**
     * 根据第一阶段返回的 ID 批量回表；调用方负责恢复第一阶段的顺序。
     */
    List<TuiDynamic> selectByIds(@Param("ids") List<Long> ids);

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
    List<Long> selectHotDynamicIds(@Param("limit") Integer limit);
}
