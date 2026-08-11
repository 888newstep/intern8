package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqMessageStatus;

import java.util.List;

@Mapper
public interface MqMessageStatusMapper {
    int insert(MqMessageStatus messageStatus);

    /**
     * Applies a state transition only when the row is still in one of the
     * expected states. This prevents stale RabbitMQ callbacks from
     * overwriting a terminal consumer result.
     */
    int transitionStatus(@Param("messageId") String messageId,
                         @Param("fromStatuses") List<Integer> fromStatuses,
                         @Param("toStatus") Integer toStatus,
                         @Param("lastError") String lastError);

    /** Claims a failed message for one compensation worker. */
    int claimForCompensation(@Param("messageId") String messageId,
                             @Param("maxRetryCount") Integer maxRetryCount,
                             @Param("staleSeconds") Integer staleSeconds);

    /** Returns publish- or consume-failed messages eligible for compensation. */
    List<MqMessageStatus> selectCompensableMessages(@Param("maxRetryCount") Integer maxRetryCount,
                                                    @Param("staleSeconds") Integer staleSeconds,
                                                    @Param("limit") Integer limit);

    int incrementRetryCount(@Param("messageId") String messageId, @Param("lastError") String lastError);
    MqMessageStatus selectByMessageId(@Param("messageId") String messageId);
}
