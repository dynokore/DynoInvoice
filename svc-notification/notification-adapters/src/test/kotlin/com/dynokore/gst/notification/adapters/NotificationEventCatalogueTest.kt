package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.events.NotificationEventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/** INV-Q-9739 A: the catalogue is built from the published artefacts and compared by equality. */
class NotificationEventCatalogueTest {
    private val asyncApi: String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(NotificationEventCatalogue.ASYNCAPI_RESOURCE))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun messageNames(document: String): Set<String> {
        val root = Yaml().load<Map<String, Any?>>(document)

        @Suppress("UNCHECKED_CAST")
        val channels = root["channels"] as Map<String, Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val components = (root["components"] as Map<String, Any?>)["messages"] as Map<String, Map<String, Any?>>
        return channels
            .filterKeys { it != "notificationExampleHappened" }
            .values
            .map { channel ->
                @Suppress("UNCHECKED_CAST")
                val ref = (channel["messages"] as Map<String, Map<String, Any?>>).values.single()["\$ref"] as String
                components.getValue(ref.removePrefix("#/components/messages/"))["name"] as String
            }.toSet()
    }

    @Test
    fun `the own entries are the messages of the contract channels without the scaffold`() {
        val own =
            NotificationEventCatalogue
                .load()
                .entries()
                .filter { it.producers == setOf(NotificationEventCatalogue.SERVICE) }
        own.map { it.eventType }.toSet() shouldBe messageNames(asyncApi) // today the empty set
    }

    @Test
    fun `a channel added to a copy of the contract is an own entry and breaks the set equality`() {
        val added =
            asyncApi
                .replace(
                    "channels:\n",
                    "channels:\n  notificationProbeHappened:\n    address: 'gst.notification.probe-happened.v1'\n" +
                        "    messages:\n      ProbeHappened:\n        \$ref: '#/components/messages/ProbeHappened'\n",
                ).replace(
                    "\ncomponents:\n  messages:\n",
                    "\ncomponents:\n  messages:\n    ProbeHappened:\n      name: ProbeHappened\n" +
                        "      payload:\n        type: object\n",
                )
        val own =
            NotificationEventCatalogue
                .build(added)
                .entries()
                .filter { it.producers == setOf(NotificationEventCatalogue.SERVICE) }
        own.map { it.eventType }.toSet() shouldBe setOf("ProbeHappened")
        (own.map { it.eventType }.toSet() == messageNames(asyncApi)) shouldBe false
    }

    @Test
    fun `every platform event with a topic is present with the same type and topic`() {
        val entries = NotificationEventCatalogue.load().entries().associateBy { it.eventType }
        val expected = PlatformEventCatalogue.ALL.filter { it.topic != null }
        (expected.isNotEmpty()) shouldBe true
        expected.filter { entries[it.eventType]?.topic != it.topic }.shouldBeEmpty()
    }

    @Test
    fun `the scaffold is no entry and a look-alike type is no entry`() {
        val types =
            NotificationEventCatalogue
                .load()
                .entries()
                .map { it.eventType }
                .toSet()
        types.contains("com.dynokore.gst.notification.ExampleHappened.v1") shouldBe false
        types.contains("com.dynokore.gst.platform.AuditEntryRecorded.v1") shouldBe true
        types.contains("com.dynokore.gst.platform.AuditEntryRecorded.v2") shouldBe false
        types.contains("com.dynokore.gst.notification.AuditEntryRecorded.v1") shouldBe false
    }

    @Test
    fun `an unsupported message reference is refused`() {
        shouldThrow<IllegalArgumentException> {
            NotificationEventCatalogue.build(
                asyncApi
                    .replace("notificationExampleHappened", "notificationProbe")
                    .replace("#/components/messages/ExampleHappened", "other.yaml#/X"),
            )
        }
    }
}
