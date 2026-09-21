package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wxx.aidocumentagent.ingestion.domain.OutboxEventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentIngestionOutboxEventRepository extends JpaRepository<DocumentIngestionOutboxEvent, Long> {

    @Query("select event.eventId from DocumentIngestionOutboxEvent event where event.status = :status "
            + "and event.availableAt <= :now order by event.id")
    List<String> findDispatchableEventIds(@Param("status") OutboxEventStatus status,
                                          @Param("now") LocalDateTime now,
                                          Pageable pageable);

    @Query("select event.eventId from DocumentIngestionOutboxEvent event where event.status = :status "
            + "and event.lockedAt < :lockedBefore order by event.id")
    List<String> findExpiredLeaseEventIds(@Param("status") OutboxEventStatus status,
                                          @Param("lockedBefore") LocalDateTime lockedBefore,
                                          Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from DocumentIngestionOutboxEvent event where event.eventId = :eventId")
    Optional<DocumentIngestionOutboxEvent> findLockedByEventId(@Param("eventId") String eventId);

    Optional<DocumentIngestionOutboxEvent> findByEventId(String eventId);
}
