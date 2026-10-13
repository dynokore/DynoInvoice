package com.dynokore.platform.tenancyjooq

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * Registers [TenancyProbe] over the application's `dataSource` bean (the runtime login) in every Spring test that has
 * tenancy-jooq's test fixtures on its classpath, so a TPL-14 test injects it (`@Autowired val probe: TenancyProbe`)
 * (coordinator ruling INV-Q-4140). The test fixtures are never on a main or runtime classpath (platform-build's
 * `verifyNoTenancyProbeOnMainClasspath`), so this auto-configuration never reaches a service.
 */
@AutoConfiguration(afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@ConditionalOnBean(name = ["dataSource"])
class TenancyProbeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun tenancyProbe(
        @Qualifier("dataSource") dataSource: DataSource,
    ): TenancyProbe = TenancyProbe.over(dataSource)
}
