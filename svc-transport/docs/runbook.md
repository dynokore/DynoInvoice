# transport-svc runbook: Access Point connections

One section per Access Point connection. Each states the expected acknowledgement latency and the AP's
failure-management protocol, agreed with the AP and captured at connection set-up (INV-REQ-565 behaviour 1 and 2,
INV-REQ-798, INV-REQ-1309). The values below are the ones the connection is configured with (variation point
VP-48); a section is reviewed before go-live and its values must equal the configuration.

## Connection: certification-kit AP simulator (adapter `primary`)

| Item | Value |
| --- | --- |
| Expected acknowledgement latency | 25 seconds (the request timeout of the adapter; `transport.ap.timeout`) |
| Failure-management protocol | The AP answers a submission with a receipt (custody) or a structural refusal. No answer inside the timeout, an outage, an authentication failure or any other status is a transient failure: the attempt is logged, the Doc UUID and version stay with submission-svc, which retries by its own state machine; the retry goes to the adapter of the first attempt. The AP's own failure handling takes over where its documented protocol says so; transport never retries past that boundary. |
| Failure rate window | 1 hour (`transport.ap.metrics-window`) |
| Throughput ceiling | The rate agreed with the AP, or a lower tenant value (INV-REQ-1317) |
