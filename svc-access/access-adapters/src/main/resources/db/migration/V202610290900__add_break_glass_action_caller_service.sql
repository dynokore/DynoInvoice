SET lock_timeout = '5s';

-- The verified calling service of a recorded break-glass action (INV-Q-9113 A); null for rows recorded before this column.
ALTER TABLE access.break_glass_action
    ADD COLUMN caller_service text CHECK (caller_service IS NULL OR length(caller_service) BETWEEN 1 AND 64);
