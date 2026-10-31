package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The caller of the release operations is decided by the permission key and by no role name (INV-Q-11183 A). */
class ReleasePermissionTest {
    private val request = RequestContext("corr-1", "cause-1")
    private val asked = ArrayList<String>()
    private val trains =
        object : TrainRepository {
            override fun find(version: TrainVersion): ReleaseTrain? = null

            override fun save(train: ReleaseTrain) = Unit
        }
    private val windows =
        object : SupportWindowRepository {
            override fun find(windowVersion: String): SupportWindow? = null

            override fun save(window: SupportWindow) = Unit
        }
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)

    private fun publishWith(permissions: OperatorPermissions) =
        PublishTrain(trains, permissions, { null }, { error("the accreditation port is not asked") }, clock)

    private val publishCommand =
        PublishTrainCommand(ActorId("rita"), TrainVersion("2026.10.0"), TrainKind.TRAIN, false, false, request)
    private val windowCommand =
        SetSupportWindowCommand(ActorId("rita"), SupportWindow("2026.2", listOf(TrainVersion("2026.10.0"))), request)

    private fun allowing(
        key: String,
        actor: String = "rita",
    ) = OperatorPermissions { who, asked ->
        this.asked += asked
        who == actor && asked == key
    }

    @Test
    fun `a stub that allows exactly the publish key lets both operations run and only that key is asked`() {
        val stub = allowing(ReleasePermission.PUBLISH)
        publishWith(stub).permits("rita", publishCommand) shouldBe true
        SetSupportWindow(windows, stub).permits("rita", windowCommand) shouldBe true
        asked.toSet() shouldBe setOf(ReleasePermission.PUBLISH)
    }

    @Test
    fun `a stub that allows only the certify key gives no permission for both operations`() {
        val stub = allowing(ReleasePermission.CERTIFY)
        publishWith(stub).permits("rita", publishCommand) shouldBe false
        SetSupportWindow(windows, stub).permits("rita", windowCommand) shouldBe false
    }

    @Test
    fun `a look-alike of the publish key allowed alone gives no permission`() {
        listOf("release-train.publish ", "Release-train.publish", "release-train.publishes").forEach { key ->
            val stub = allowing(key)
            publishWith(stub).permits("rita", publishCommand) shouldBe false
            SetSupportWindow(windows, stub).permits("rita", windowCommand) shouldBe false
        }
    }

    @Test
    fun `a stub that allows the key for another actor than the command actor gives no permission`() {
        val stub = allowing(ReleasePermission.PUBLISH, actor = "sam")
        publishWith(stub).permits("rita", publishCommand) shouldBe false
        publishWith(stub).permits("sam", publishCommand) shouldBe false // the command names rita
        SetSupportWindow(windows, stub).permits("rita", windowCommand) shouldBe false
    }

    @Test
    fun `the constants are the two release keys`() {
        ReleasePermission.PUBLISH shouldBe "release-train.publish"
        ReleasePermission.CERTIFY shouldBe "release-train.certify"
    }
}
