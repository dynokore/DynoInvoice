package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.contract.api.IntakeApi
import org.springframework.web.bind.annotation.RestController

/**
 * The intake operation of the aggregation contract (TPL-08). It answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class IntakeController : IntakeApi
