package com.wxx.aidocumentagent.reading;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ReadingDispatchLockRepository extends JpaRepository<ReadingDispatchLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReadingDispatchLock d where d.id=1")
    Optional<ReadingDispatchLock> acquire();
}
