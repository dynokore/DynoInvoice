-- INV-T-26224 (INV-REQ-316, INV-REQ-2445; slice 1). The held-documents replica keeps the RUL-011 mark: true exactly
-- while the latest fact by docsequence is a DocumentHeld whose holdReasons contain RUL-011. The grant is unchanged.

ALTER TABLE masterdata.held_document ADD COLUMN rul011 boolean NOT NULL DEFAULT false;
