package com.wxx.aidocumentagent.reading;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ReadingNoteRepository extends JpaRepository<ReadingNote, String> {
    Optional<ReadingNote> findByTaskId(String taskId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from ReadingNote n where n.taskId=:taskId")
    Optional<ReadingNote> lock(String taskId);
}
