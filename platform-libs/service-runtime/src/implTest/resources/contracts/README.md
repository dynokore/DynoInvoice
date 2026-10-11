Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged at
platform-contracts commit 6915382 (INV-T-2875; the head of platform-contracts !40, INV-T-3742, which adds the SRT
catalogue rows at gst-catalogue 1.12.0; to be repinned to main once it merges): `service-runtime/openapi.yaml`
(runJobNow, pauseJob, resumeJob), the payload schemas of PolicyChanged, ConnectionStatusChanged and
EvidenceArtefactRecorded, the error envelope, and `catalogue/errors/error-codes.yaml` (the SRT rows). Regenerate by
copying the files from a newer commit and updating the commit here; never hand-edit.
