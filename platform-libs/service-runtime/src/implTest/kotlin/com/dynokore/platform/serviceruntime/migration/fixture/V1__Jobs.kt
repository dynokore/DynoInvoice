package com.dynokore.platform.serviceruntime.migration.fixture

import com.dynokore.platform.serviceruntime.jobs.JobSchema
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** The fixture service's migration (TPL-12): the job tables of [JobSchema] in `ledger`, for MigrationRunnerIT. */
@Suppress("ClassName")
class V1__Jobs : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use {
            it.execute(
                JobSchema.ddl("ledger", "submission_runtime", "submission_sys"),
            )
        }
    }
}
