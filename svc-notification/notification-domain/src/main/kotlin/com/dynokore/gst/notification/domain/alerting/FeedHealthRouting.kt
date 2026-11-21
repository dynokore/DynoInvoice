package com.dynokore.gst.notification.domain.alerting

/** A feed health event carries COVERAGE, a hard-stop class: rerouted, never disabled (INV-REQ-910, INV-REQ-1114). */
val FEED_ALERT_CLASS: AlertClass = AlertClass.COVERAGE

/** The default-recipient kind of a FeedHealthChanged control (INV-REQ-910); any string outside the five is refused. */
fun feedAlertKind(control: String): DefaultAlertKind =
    when (control) {
        "SEQUENCE", "COUNT", "VALUE" -> DefaultAlertKind.FEED_STALL
        "WATERMARK" -> DefaultAlertKind.WATERMARK_GAP
        "MANIFEST" -> DefaultAlertKind.MANIFEST_FAILURE
        else -> throw IllegalArgumentException("Unknown feed control: '$control'")
    }
