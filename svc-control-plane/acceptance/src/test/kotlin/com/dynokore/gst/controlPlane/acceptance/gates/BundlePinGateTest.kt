package com.dynokore.gst.controlPlane.acceptance.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BundlePinGateTest {
    private val pin = "0.0.0-dev+3030c14b"
    private val record = "$pin\n"

    private fun toml(line: String = "platformContractsBundle = \"$pin\"") =
        "springBoot = \"4.1.1\"\n$line\nkotlin = \"2.0\"\n"

    private fun differs(
        value: String,
        recordLine: String = pin,
    ) = Finding(
        BundlePinGate.NAME,
        "platformContractsBundle",
        "$value in gradle/libs.versions.toml differs from $recordLine in bundle-pin.txt",
    )

    private fun assigned(n: Int) =
        Finding(
            BundlePinGate.NAME,
            "platformContractsBundle",
            "platformContractsBundle is assigned $n times, expected 1",
        )

    private fun oneLine() = Finding(BundlePinGate.NAME, BundlePinGate.NAME, "the pin record holds exactly one line")

    private fun missing() = Finding(BundlePinGate.NAME, BundlePinGate.NAME, "the pin record is missing or empty")

    @Test
    fun `INV-AC-12888 equal pin and record give no finding`() {
        BundlePinGate.check(toml(), record).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-12888 pin d6181f83 against the record is one finding`() {
        BundlePinGate.check(toml("platformContractsBundle = \"0.0.0-dev+d6181f83\""), record) shouldBe
            listOf(differs("0.0.0-dev+d6181f83"))
    }

    @Test
    fun `INV-AC-12888 pin ad00fb84 against the record is one finding`() = differing("0.0.0-dev+ad00fb84")

    @Test
    fun `INV-AC-12888 pin 290ba4ba against the record is one finding`() = differing("0.0.0-dev+290ba4ba")

    @Test
    fun `INV-AC-12888 pin 3030c14 of seven characters against the record is one finding`() =
        differing("0.0.0-dev+3030c14")

    @Test
    fun `INV-AC-12888 pin 3030c14c against the record is one finding`() = differing("0.0.0-dev+3030c14c")

    private fun differing(value: String) {
        BundlePinGate.check(toml("platformContractsBundle = \"$value\""), record) shouldBe listOf(differs(value))
    }

    @Test
    fun `INV-AC-12888 a record with a trailing space names both values`() {
        BundlePinGate.check(toml(), "$pin \n") shouldBe listOf(differs(pin, "$pin "))
    }

    @Test
    fun `INV-AC-12888 a record without the final newline holds exactly one line`() {
        BundlePinGate.check(toml(), pin) shouldBe listOf(oneLine())
    }

    @Test
    fun `INV-AC-12888 a record with two lines holds exactly one line`() {
        BundlePinGate.check(toml(), "$pin\n$pin\n") shouldBe listOf(oneLine())
    }

    @Test
    fun `INV-AC-12888 a record with a trailing blank line holds exactly one line`() {
        BundlePinGate.check(toml(), "$pin\n\n") shouldBe listOf(oneLine())
    }

    @Test
    fun `INV-AC-12888 a null record is missing`() {
        BundlePinGate.check(toml(), null) shouldBe listOf(missing())
    }

    @Test
    fun `INV-AC-12888 a blank record is missing`() {
        BundlePinGate.check(toml(), " \n") shouldBe listOf(missing())
        BundlePinGate.check(toml(), "") shouldBe listOf(missing())
    }

    @Test
    fun `INV-AC-12888 a null versions file is a finding`() {
        BundlePinGate.check(null, record) shouldBe
            listOf(
                Finding(
                    BundlePinGate.NAME,
                    "gradle/libs.versions.toml",
                    "gradle/libs.versions.toml is missing or empty",
                ),
            )
    }

    @Test
    fun `INV-AC-12888 the look-alike key platformContractBundle is assigned 0 times`() {
        BundlePinGate.check(toml("platformContractBundle = \"$pin\""), record) shouldBe listOf(assigned(0))
    }

    @Test
    fun `INV-AC-12888 the key assigned twice is assigned 2 times`() {
        val twice = toml() + "platformContractsBundle = \"$pin\"\n"
        BundlePinGate.check(twice, record) shouldBe listOf(assigned(2))
    }

    @Test
    fun `INV-AC-12888 the key only inside a comment is assigned 0 times`() {
        BundlePinGate.check(toml("# platformContractsBundle = \"$pin\""), record) shouldBe listOf(assigned(0))
    }

    @Test
    fun `INV-AC-12888 the value of another key is assigned 0 times`() {
        BundlePinGate.check(toml("gstCatalogue = \"$pin\""), record) shouldBe listOf(assigned(0))
    }
}
