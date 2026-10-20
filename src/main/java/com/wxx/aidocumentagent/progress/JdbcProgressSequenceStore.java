package com.wxx.aidocumentagent.progress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A row lock serializes sequence allocation across workers and survives application restarts. */
@Repository
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JdbcProgressSequenceStore implements ProgressSequenceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcProgressSequenceStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public long next(ProgressStream stream) {
        return transaction.execute(status -> {
            jdbc.update("INSERT IGNORE INTO progress_sequence "
                            + "(resource_type, execution_id, knowledge_base_id, sequence_no) VALUES (?, ?, ?, 0)",
                    stream.resource().name(), stream.executionId(), stream.knowledgeBaseId());
            jdbc.update("UPDATE progress_sequence SET sequence_no = sequence_no + 1 "
                            + "WHERE resource_type = ? AND execution_id = ? AND knowledge_base_id = ?",
                    stream.resource().name(), stream.executionId(), stream.knowledgeBaseId());
            return jdbc.queryForObject("SELECT sequence_no FROM progress_sequence "
                            + "WHERE resource_type = ? AND execution_id = ? AND knowledge_base_id = ?",
                    Long.class, stream.resource().name(), stream.executionId(), stream.knowledgeBaseId());
        });
    }

    @Override
    public long current(ProgressStream stream) {
        return jdbc.query("SELECT sequence_no FROM progress_sequence "
                        + "WHERE resource_type = ? AND execution_id = ? AND knowledge_base_id = ?",
                result -> result.next() ? result.getLong(1) : 0L,
                stream.resource().name(), stream.executionId(), stream.knowledgeBaseId());
    }
}
