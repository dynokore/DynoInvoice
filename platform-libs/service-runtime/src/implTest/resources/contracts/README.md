Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged:
`service-runtime/openapi.yaml` (runJobNow, pauseJob, resumeJob, rebuildConsumer, setPolicy, acknowledgeControlRelaxation) at platform-contracts
main commit 36f9e69 (INV-T-5179; service-runtime contract 1.3.0; before it 0b685b5, INV-T-3819, contract 1.2.0); the
payload schemas of PolicyChanged, ConnectionStatusChanged and EvidenceArtefactRecorded (unchanged at 36f9e69); the
error envelope (unchanged at 81ab6f4) and `catalogue/errors/error-codes.yaml` (the SRT rows, SRT-001..SRT-012, and the POL rows, POL-001..POL-024) at
platform-contracts main commit 36f9e69 (gst-catalogue 1.16.0; POL rows by INV-T-5179, coordinator ruling INV-Q-5172;
before it 81ab6f4, gst-catalogue 1.15.0). Regenerate by copying the files from a newer commit and updating the commit here; never hand-edit.
