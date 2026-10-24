-- Preserve unassigned legacy rows without guessing who created them.
-- NOT VALID skips checking those existing rows, while inserts and updates
-- must supply an owner. The existing foreign key still checks that user exists.
ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_owner_required
        CHECK (owner_id IS NOT NULL) NOT VALID;
