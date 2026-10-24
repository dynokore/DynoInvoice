package com.dynokore.gst.notification.adapters.http

import com.dynokore.gst.notification.contract.api.DeliveriesApi
import org.springframework.web.bind.annotation.RestController

/**
 * The `deliveries` operations of the notification contract (TPL-08). Every operation answers 501 until the task named
 * for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class DeliveriesController : DeliveriesApi
