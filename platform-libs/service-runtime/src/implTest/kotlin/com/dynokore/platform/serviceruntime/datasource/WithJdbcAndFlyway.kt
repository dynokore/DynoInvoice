package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.composition.StandInProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.platform
import com.dynokore.platform.serviceruntime.composition.sorted
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.core.type.AnnotationMetadata

class WithJdbcAndFlyway : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        sorted(
            platform() + DataSourceAutoConfiguration::class.java + FlywayAutoConfiguration::class.java +
                StandInProductRuntimeConfiguration::class.java,
        )
}
