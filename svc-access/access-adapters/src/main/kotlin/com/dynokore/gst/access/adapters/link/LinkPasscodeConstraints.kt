package com.dynokore.gst.access.adapters.link

import com.dynokore.gst.access.application.link.SmsGatewayBinding
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.LocalDate

/**
 * What `setPolicy` asks of access-svc for VP-75 (INV-REQ-2758): the default and a smaller limit of wrong entries are
 * accepted (the catalogue validator has already bounded the limit to 1 to 4). SMS only is accepted only when the
 * property `access.signed-link.sms-gateway-bound` is true (INV-REQ-2758 behaviour 3, constraint VP-75.SMS_GATEWAY,
 * ruling INV-Q-17203 A); absent is false. Any other variation point is not this check's to judge and gets no verdict.
 */
class LinkPasscodeConstraints(
    private val smsGateway: SmsGatewayBinding,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        when {
            key != VP_75 -> {
                null
            }

            selected.any { it.key == SMS_ONLY } && !SmsGatewayBinding.acceptsSmsOnly(smsGateway) -> {
                ConstraintVerdict.Refuse(
                    "SMS only needs an SMS gateway bound to MessageDelivery: " +
                        "access.signed-link.sms-gateway-bound is not true",
                )
            }

            else -> {
                ConstraintVerdict.Accept
            }
        }

    private companion object {
        const val SMS_ONLY = "SMS_ONLY"
    }
}
