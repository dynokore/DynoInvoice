package com.dynokore.platform.serviceruntime.binding

/**
 * The two deployment forms of INV-REQ-34/FR-H.1: one codebase, one service set,
 * differences confined to configuration (ARC-11, INV-ADR-276). A binding whose
 * two forms genuinely need different code (unlike [SecretStore] or
 * [TelemetryExport], each "one code path in both forms") selects between its two
 * implementations by this value — read from `gst.form`, the same property name
 * TPL-16's `values-private.yaml` fragment sets under `global.form`.
 */
enum class DeploymentForm { SAAS, PRIVATE }
