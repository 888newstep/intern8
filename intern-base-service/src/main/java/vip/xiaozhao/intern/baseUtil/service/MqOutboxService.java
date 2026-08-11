package vip.xiaozhao.intern.baseUtil.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import vip.xiaozhao.intern.baseUtil.intf.constant.MqOutboxStatusConstant;
import vip.xiaozhao.intern.baseUtil.intf.entity.MqOutbox;
import vip.xiaozhao.intern.baseUtil.intf.mapper.MqOutboxMapper;
import vip.xiaozhao.intern.baseUtil.mq.event.BaseMqEvent;

import java.util.Date;
import java.util.Objects;

/**
 * Writes an MQ event into the transactional outbox.
 *
 * <p>The method deliberately does not start a new transaction. When it is
 * called from a business service, MyBatis participates in that business
 * transaction, so a committed business row always has a committed outbox
 * row, and a rolled-back business row cannot leave a publishable event.</p>
 */
@Service
public class MqOutboxService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(MqOutboxService.class);

    private final MqOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Counter enqueuedCounter;

    @Autowired
    public MqOutboxService(MqOutboxMapper outboxMapper,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           @Value("${mq.outbox.enabled:true}") boolean enabled) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.enqueuedCounter = Counter.builder("mq.outbox.enqueued")
                .description("Events inserted into the transactional MQ outbox")
                .register(meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry);
    }

    /**
     * Lightweight constructor for unit tests and legacy callers.
     */
    public MqOutboxService(MqOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this(outboxMapper, objectMapper, new SimpleMeterRegistry(), true);
    }

    /**
     * Returns false only when the feature is explicitly disabled. Persistence
     * failures are propagated so the surrounding business transaction rolls
     * back instead of silently falling back to the old after-commit window.
     */
    public boolean enqueue(BaseMqEvent event, String exchangeName, String routingKey) {
        if (!enabled) {
            logger.warn("Transactional MQ outbox is disabled; caller must use its explicit compatibility path");
            return false;
        }
        validate(event, exchangeName, routingKey);

        final String messageBody = serialize(event);
        MqOutbox outbox = new MqOutbox();
        Date now = new Date();
        outbox.setEventId(event.getEventId());
        outbox.setEventType(event.getEventType());
        outbox.setExchangeName(exchangeName);
        outbox.setRoutingKey(routingKey);
        outbox.setMessageBody(messageBody);
        outbox.setStatus(MqOutboxStatusConstant.PENDING);
        outbox.setRetryCount(0);
        outbox.setNextAttemptTime(now);
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);

        try {
            outboxMapper.insert(outbox);
            enqueuedCounter.increment();
            return true;
        } catch (DuplicateKeyException duplicateKeyException) {
            // Event IDs are the idempotency boundary. A collision is safe only
            // when every immutable routing/payload field is identical.
            MqOutbox existing = outboxMapper.selectByEventId(event.getEventId());
            if (sameEvent(existing, outbox)) {
                logger.debug("MQ outbox event already exists, eventId={}", event.getEventId());
                return true;
            }
            throw new IllegalStateException("MQ outbox event ID collision: " + event.getEventId(),
                    duplicateKeyException);
        } catch (RuntimeException exception) {
            logger.error("Failed to persist MQ outbox event; business transaction must roll back, eventId={}",
                    event.getEventId(), exception);
            throw exception;
        }
    }

    private void validate(BaseMqEvent event, String exchangeName, String routingKey) {
        if (event == null || isBlank(event.getEventId()) || isBlank(event.getEventType())) {
            throw new IllegalArgumentException("MQ outbox event metadata is incomplete");
        }
        if (isBlank(exchangeName) || isBlank(routingKey)) {
            throw new IllegalArgumentException("MQ outbox route is incomplete");
        }
    }

    private String serialize(BaseMqEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize MQ outbox event: " + event.getEventId(), exception);
        }
    }

    private boolean sameEvent(MqOutbox existing, MqOutbox expected) {
        return existing != null
                && Objects.equals(existing.getEventId(), expected.getEventId())
                && Objects.equals(existing.getEventType(), expected.getEventType())
                && Objects.equals(existing.getExchangeName(), expected.getExchangeName())
                && Objects.equals(existing.getRoutingKey(), expected.getRoutingKey())
                && Objects.equals(existing.getMessageBody(), expected.getMessageBody());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
