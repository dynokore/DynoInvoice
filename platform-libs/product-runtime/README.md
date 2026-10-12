# product-runtime

—

| | |
| --- | --- |
| Layer | L3 (SDS §18.9) |
| Embedded by | Every back-end service |
| Part used | Replica feeds and fallbacks, the platform-event catalogue, and the fence participants of the train (§18.9) |
| Uses | service-runtime, config-resolver, outbox-and-queue, tenancy-kernel, gst-catalogue |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Use

A service puts `com.dynokore.platform:product-runtime` on its classpath and imports `ProductRuntimeConfiguration`
after `ServiceRuntimeConfiguration` as TPL-18 writes it (it is also an auto-configuration and relies on no ordering;
config-resolver takes its catalogue in either order from service-runtime 3.2.1, INV-T-3881). It wires:

- the `VariationPointCatalogue` over gst-catalogue (`gst.service` names the rows `keys()` lists) and the
  `DelegationProbe` over the authority replica, which config-resolver resolves through;
- `StructureFeedHandler` and `AuthorityFeedHandler` (inbox `EventHandler`s for the topics in `ReplicaFeeds`) and
  `PolicyFeed` (PolicyChanged straight off its producer topic);
- the tenancy-svc and access-svc fallbacks, once `product-runtime.fallback.tenancy-svc-url` and
  `product-runtime.fallback.access-svc-url` are set; without them a replica serves what it holds.

The generated contract types come from the platform-contracts bundle pinned as `platformContractsBundle` in
`gradle/libs.versions.toml`; the build stages it as a local Maven layout under `build/contracts-repo`.
