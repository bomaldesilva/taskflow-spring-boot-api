-- Keep existing tasks unassigned: their creator cannot be inferred safely.
-- A later ownership rollout must define their treatment before requiring an owner.
ALTER TABLE tasks
    ADD COLUMN owner_id BIGINT;

ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
        ON DELETE RESTRICT;

CREATE INDEX idx_tasks_owner_id ON tasks (owner_id);
