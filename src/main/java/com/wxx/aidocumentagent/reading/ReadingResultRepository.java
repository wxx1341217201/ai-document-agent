package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingResultRepository extends JpaRepository<ReadingResult, String> {
    Optional<ReadingResult> findByIdAndTaskId(String id, String taskId);
    Page<Summary> findAllProjectedByTaskId(String taskId, Pageable pageable);
    interface Summary { String getId(); int getRoundNo(); String getCompletion(); Instant getCreatedAt(); }
}
