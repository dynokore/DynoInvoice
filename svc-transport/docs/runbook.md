# transport-svc runbook: Access Point connections

One section per Access Point connection. Each states the expected acknowledgement latency and the AP's
failure-management protocol, agreed with the AP and captured at connection set-up (INV-REQ-565 behaviour 1 and 2,
INV-REQ-798, INV-REQ-1309). The values below are the ones the connection is configured with (variation point
VP-48); a section is reviewed before go-live and its values must equal the configuration.

## Sources

The IRAS API interface specifications and the InvoiceNow Technical Playbook that transport-svc is built against
(INV-REQ-420). The versions are marked `provisional` until the documents are held; obtaining them is not recorded here.

| Source | Version |
| --- | --- |
| Invoice Data Submission API Interface Specs (R3, IRAS, APEX portal) | not yet obtained (`provisional`) |
| InvoiceNow Technical Playbook (IMDA) | not yet obtained (`provisional`) |

Both documents are obtained through the Access Point, which already holds both.

## Connection: certification-kit AP simulator (adapter `primary`)

| Item | Value |
| --- | --- |
| Expected acknowledgement latency | 25 seconds (the request timeout of the adapter; `transport.ap.timeout`) (`provisional`) |
| Failure-management protocol | The AP answers a submission with a receipt (custody) or a structural refusal. No answer inside the timeout, an outage, an authentication failure or any other status is a transient failure: the attempt is logged, the Doc UUID and version stay with submission-svc, which retries by its own state machine; the retry goes to the adapter of the first attempt. The AP's own failure handling takes over where its documented protocol says so; transport never retries past that boundary. (`provisional`) |
| Failure rate window | 1 hour (`transport.ap.metrics-window`) |
| Throughput ceiling | The rate agreed with the AP, or a lower tenant value (INV-REQ-1317) |
