-- V202611171600__add_period_span_period_id.sql (INV-T-21536; INV-AC-21535). Expand-only: no default, no backfill.
ALTER TABLE canonical.period_span ADD COLUMN period_id uuid NULL;
