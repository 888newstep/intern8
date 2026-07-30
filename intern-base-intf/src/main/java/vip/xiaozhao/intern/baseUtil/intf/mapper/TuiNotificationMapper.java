package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.TuiNotification;

import java.util.List;

@Mapper
public interface TuiNotificationMapper {

    void insert(TuiNotification notification);

    TuiNotification selectById(@Param("id") Long id);

    List<TuiNotification> selectByUserId(@Param("userId") Long userId, @Param("cursor") Long cursor, @Param("limit") Integer limit);

    void updateIsRead(@Param("userId") Long userId);

    void deleteByTargetId(@Param("targetId") String targetId);

    void deleteById(@Param("id") Long id);

    Integer countUnread(@Param("userId") Long userId);
}