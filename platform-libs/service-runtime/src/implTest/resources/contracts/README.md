Pinned copies of platform-contracts files the service-runtime tests validate against, taken unchanged at
platform-contracts main commit a7ac958 (INV-T-2875; gst-catalogue 1.12.0, released with INV-T-3742's SRT rows):
`service-runtime/openapi.yaml` (runJobNow, pauseJob, resumeJob), the payload schemas of PolicyChanged,
ConnectionStatusChanged and EvidenceArtefactRecorded, the error envelope, and `catalogue/errors/error-codes.yaml`
(the SRT rows). Regenerate by copying the files from a newer commit and updating the commit here; never hand-edit.
