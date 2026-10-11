Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged:
`service-runtime/openapi.yaml` (runJobNow, pauseJob, resumeJob, rebuildConsumer) at platform-contracts main commit
0b685b5 (INV-T-3819; service-runtime contract 1.2.0 with ReplayState.producers, owner ruling INV-Q-3788 A); the
payload schemas of PolicyChanged, ConnectionStatusChanged and EvidenceArtefactRecorded (unchanged at 0b685b5), the
error envelope, and `catalogue/errors/error-codes.yaml` (the SRT rows) at a7ac958 (INV-T-2875; gst-catalogue 1.12.0,
released with INV-T-3742's SRT rows). Regenerate by copying the files from a newer commit and updating the commit here; never hand-edit.
