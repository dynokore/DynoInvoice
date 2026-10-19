package com.dynokore.gst.rules.domain

import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate
import java.util.UUID

internal val TENANT_A: TenantId = TenantId(UUID.fromString("00000000-0000-7000-8000-00000000000a"))

internal fun version(
    type: PackType,
    label: String,
    from: LocalDate,
    to: LocalDate? = null,
    tenant: TenantId? = null,
): PackVersion =
    PackVersion(
        PackManifest(
            packId = PackId("${type.value}-pack"),
            packType = type,
            version = PackVersionLabel(label),
            effectiveFrom = from,
            effectiveTo = to,
            minEngineVersion = "1.0.0",
        ),
        tenant,
    )

internal fun registryOf(vararg versions: PackVersion): InMemoryPackRegistry =
    InMemoryPackRegistry().also { registry -> versions.forEach(registry::register) }
