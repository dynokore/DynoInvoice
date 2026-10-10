package com.dynokore.platform.outboxandqueue

/**
 * One integration event as the service's mapper (TPL-05) produces it: the contract
 * type (`com.dynokore.gst.<service>.<Name>.v<N>`, API-40) and its payload as JSON
 * text already shaped by the producer's generated contract type (INV-REQ-815).
 */
data class OutboundEvent(
    val type: String,
    val dataJson: String,
)
