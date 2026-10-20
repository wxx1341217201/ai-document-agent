CREATE TABLE reading_task (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    parent_task_id VARCHAR(36) NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    goal VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completion VARCHAR(16) NOT NULL DEFAULT 'NONE',
    round_no INT NOT NULL DEFAULT 1,
    scope_json JSON NOT NULL,
    checkpoint_json JSON NOT NULL,
    deadline_at DATETIME(3) NULL,
    lease_until DATETIME(3) NULL,
    run_token VARCHAR(36) NULL,
    latest_result_id VARCHAR(36) NULL,
    error_message VARCHAR(256) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_reading_request (knowledge_base_id, request_key),
    KEY idx_reading_list (knowledge_base_id, updated_at DESC, id DESC),
    KEY idx_reading_queue (status, created_at, id),
    KEY idx_reading_session (knowledge_base_id, session_id, created_at),
    CONSTRAINT fk_reading_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE,
    CONSTRAINT ck_reading_status CHECK (status IN ('PENDING','RUNNING','INTERRUPTED','SUCCEEDED','CANCELLED')),
    CONSTRAINT ck_reading_completion CHECK (completion IN ('FULL','PARTIAL','NONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reading_result (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    completion VARCHAR(16) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_reading_result (task_id, created_at DESC, id DESC),
    CONSTRAINT fk_reading_result_task FOREIGN KEY (task_id) REFERENCES reading_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reading_note (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    sources_json JSON NOT NULL,
    adopted_result_id VARCHAR(36) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_reading_note_task (task_id),
    CONSTRAINT fk_reading_note_task FOREIGN KEY (task_id) REFERENCES reading_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reading_note_revision (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    note_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    sources_json JSON NOT NULL,
    source_result_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_reading_note_revision (note_id, revision DESC),
    CONSTRAINT fk_reading_revision_note FOREIGN KEY (note_id) REFERENCES reading_note(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reading_dispatch_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO reading_dispatch_lock(id) VALUES(1);
