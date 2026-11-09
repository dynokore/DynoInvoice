package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** INV-T-18775: the rate import record (INV-REQ-833, API-17): `getImportRecord` and the 202 answer. */
internal class RateImportRecordTest : RateImportHarness() {
    @Test
    fun `INV-AC-8626 a load with one invalid rate is 201 and its record lists the rejected row with MDM-015`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val loaded = env.post(body("MAS", d0, d0.plusDays(1) to "0", d0.plusDays(2) to "1.36", entity = entity))
            assertThat(loaded.status).describedAs(loaded.body).isEqualTo(201)
            val importId = loaded.node.get("importId").asString()
            val (read, _) = env.record(importId)
            assertThat(read.status).describedAs(read.body).isEqualTo(200)
            assertThat(read.node.get("importKind").asString()).isEqualTo("RATES")
            assertThat(read.node.get("state").asString()).isEqualTo("SUCCEEDED")
            assertThat(read.node.get("source").asString()).isEqualTo("MAS")
            assertThat(
                read.node
                    .get("result")
                    .get("appliedCount")
                    .asInt(),
            ).isEqualTo(1)
            assertThat(
                read.node
                    .get("result")
                    .get("failedCount")
                    .asInt(),
            ).isEqualTo(1)
            val rejected = read.node.get("rejectedRows")
            assertThat(rejected.size()).isEqualTo(1)
            assertThat(rejected.get(0).get("row").asInt()).isEqualTo(1)
            assertThat(rejected.get(0).get("code").asString()).isEqualTo(ErrorCode.MDM_015.code)
            // a second caller, even the look-alike principal holding the same keys, finds no such record
            assertThat(env.record(importId, TestPermissions.LOOKALIKE_OPERATOR).first.status).isEqualTo(404)
            assertThat(env.record(importId, TestPermissions.OTHER_OPERATOR).first.status).isEqualTo(404)
        }

    @Test
    fun `INV-AC-8626 a refused import or a caller without exchange-rate import leaves 0 rate import records`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val refused = env.post(body("MAS2", d0, d0.plusDays(1) to "1.35", entity = entity))
            assertThat(refused.code).isEqualTo(ErrorCode.MDM_012.code)
            val denied = env.post(body("MAS", d0, d0.plusDays(1) to "1.35", entity = entity), actor = "IDN-NOBODY")
            assertThat(denied.status).isEqualTo(403)
            assertThat(env.importRecords()).isZero()
        }

    @Test
    fun `INV-AC-8626 a load held beyond the wait answers 202 with Location, Retry-After and the RUNNING record`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            saveDelays["MAS"] = LATE_MILLIS
            val response = env.postRaw(body("MAS", d0, d0.plusDays(1) to "1.35", entity = entity))
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(202)
            val location = checkNotNull(response.getHeader("Location"))
            assertThat(location).startsWith("/internal/v1/masterdata/imports/IMP-")
            assertThat(response.getHeader("Retry-After")).isNotBlank()
            val running = mapper.readTree(response.contentAsString)
            assertThat(running.get("importKind").asString()).isEqualTo("RATES")
            assertThat(running.get("state").asString()).isEqualTo("RUNNING")
            assertThat(running.get("source").asString()).isEqualTo("MAS")
            assertThat(running.get("dateRange").get("from").asString()).isEqualTo(d0.toString())
            val id = location.substringAfterLast('/')
            assertThat(
                env
                    .record(id)
                    .first.node
                    .get("state")
                    .asString(),
            ).isEqualTo("RUNNING")
            val state = env.awaitDone(id)
            assertThat(state).isEqualTo("SUCCEEDED")
            assertThat(env.rateRows("MAS")).isEqualTo(1)
        }
}
