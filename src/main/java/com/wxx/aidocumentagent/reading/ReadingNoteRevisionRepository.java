package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingNoteRevisionRepository extends JpaRepository<ReadingNoteRevision, String> {
    Page<Summary> findAllProjectedByNoteId(String noteId, Pageable pageable);
    Optional<ReadingNoteRevision> findByIdAndNoteId(String id, String noteId);
    interface Summary { String getId(); long getRevision(); String getSourceResultId(); Instant getCreatedAt(); }
}
