Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged:
`service-runtime/openapi.yaml` (runJobNow, pauseJob, resumeJob, rebuildConsumer, setPolicy, acknowledgeControlRelaxation) at platform-contracts
main commit 36f9e69 (INV-T-5179; service-runtime contract 1.3.0; before it 0b685b5, INV-T-3819, contract 1.2.0); the
payload schemas of PolicyChanged, ConnectionStatusChanged and EvidenceArtefactRecorded (unchanged at 36f9e69); the
error envelope (unchanged at 81ab6f4) and `catalogue/errors/error-codes.yaml` (the SRT rows, SRT-001..SRT-012, the POL rows, POL-001..POL-024, and the TNC rows, TNC-001..TNC-007, INV-T-7557, INV-T-8921, INV-T-11911, and ING-022 and SUB-023, INV-T-11269, coordinator rulings INV-Q-11260 and INV-Q-11264) at
platform-contracts main commit 082db546 (INV-T-11909, TNC-005 to TNC-007, coordinator ruling INV-Q-11899 A; before it 6c276b06, INV-T-11269; d09fddca, INV-T-8919; TNC-004 by coordinator ruling INV-Q-8903; before it 4b8924e8, INV-T-7556, the TNC rows by coordinator ruling INV-Q-7553; before it 36f9e69, gst-catalogue 1.16.0; POL rows by INV-T-5179, coordinator ruling INV-Q-5172;
before it 81ab6f4, gst-catalogue 1.15.0). Regenerate by copying the files from a newer commit and updating the commit here; never hand-edit.
