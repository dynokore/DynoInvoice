package com.dynokore.gst.access.adapters.link

import com.dynokore.gst.access.application.link.LinkPasscodeSetting
import com.dynokore.gst.access.application.link.LinkPasscodeSettings
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.ObjectMapper
import java.time.Clock

internal val VP_75 = VariationPointKey("VP-75")

private const val SEPARATE_MESSAGE_5 = "SEPARATE_MESSAGE_5"
private const val SMS_ONLY = "SMS_ONLY"
private const val FEWER_WRONG_ENTRIES = "FEWER_WRONG_ENTRIES"

/**
 * VP-75 as the config-resolver resolves it at installation scope (INV-REQ-2758): SEPARATE_MESSAGE_5 is the default
 * (five wrong entries, SMS where a mobile number is on file, else a second e-mail); SMS_ONLY and FEWER_WRONG_ENTRIES
 * (a whole number 1 to 4) combine. Nothing selected stands for the default. A selected key this service does not know,
 * or a limit outside 1 to 4, is not used: the read fails and the operation that asked records nothing (fail closed;
 * never a limit above five).
 */
class VariationPointLinkSettings(
    private val reader: PolicyReader,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : LinkPasscodeSettings {
    override fun current(): LinkPasscodeSetting {
        val selected = reader.resolve(VP_75, PolicyScope.installation(), BusinessDate.today(clock)).selected
        var limit = SignedLink.MAX_WRONG_ENTRIES
        var smsOnly = false
        selected.forEach { option ->
            when (option.key) {
                SEPARATE_MESSAGE_5 -> {
                    limit = SignedLink.MAX_WRONG_ENTRIES
                }

                SMS_ONLY -> {
                    smsOnly = true
                }

                FEWER_WRONG_ENTRIES -> {
                    limit = limitOf(option.valueJson)
                }

                else -> {
                    error("VP-75 selects an option this service does not know")
                }
            }
        }
        return LinkPasscodeSetting(limit, smsOnly)
    }

    private fun limitOf(json: String?): Int {
        val node = mapper.readTree(checkNotNull(json) { "VP-75 FEWER_WRONG_ENTRIES holds no limit" })
        check(node.isIntegralNumber) { "the VP-75 limit must be a whole number" }
        val value = node.asInt()
        check(value in 1 until SignedLink.MAX_WRONG_ENTRIES) { "the VP-75 limit is 1 to 4 wrong entries" }
        return value
    }
}
