package vip.xiaozhao.intern.baseUtil.intf.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;

import java.util.Date;
import java.util.List;

@Mapper
public interface MqOutboxMapper {

    int insert(MqOutbox outbox);

    MqOutbox selectByEventId(@Param("eventId") String eventId);

    /**
     * Selects candidates only. The following conditional claim is still
     * required because multiple application instances may read the same row.
     */
    List<MqOutbox> selectDispatchable(@Param("limit") Integer limit,
                                      @Param("maxRetryCount") Integer maxRetryCount);

    int claim(@Param("id") Long id,
              @Param("leaseUntil") Date leaseUntil,
              @Param("maxRetryCount") Integer maxRetryCount);

    int markDispatched(@Param("id") Long id);

    int markFailed(@Param("id") Long id,
                   @Param("status") Integer status,
                   @Param("retryCount") Integer retryCount,
                   @Param("nextAttemptTime") Date nextAttemptTime,
                   @Param("lastError") String lastError);
}
