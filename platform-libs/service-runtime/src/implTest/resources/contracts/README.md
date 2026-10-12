Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged:
`service-runtime/openapi.yaml` (runJobNow, pauseJob, resumeJob, rebuildConsumer) at platform-contracts main commit
0b685b5 (INV-T-3819; service-runtime contract 1.2.0 with ReplayState.producers, owner ruling INV-Q-3788 A); the
payload schemas of PolicyChanged, ConnectionStatusChanged and EvidenceArtefactRecorded (unchanged at 0b685b5); the
error envelope (unchanged at 81ab6f4) and `catalogue/errors/error-codes.yaml` (the SRT rows, SRT-001..SRT-012) at
platform-contracts main commit 81ab6f4 (gst-catalogue 1.15.0; SRT-012 released by INV-T-4055, coordinator ruling
INV-Q-4059; previously fadead4, gst-catalogue 1.14.0). Regenerate by copying the files from a newer commit and updating the commit here; never hand-edit.
