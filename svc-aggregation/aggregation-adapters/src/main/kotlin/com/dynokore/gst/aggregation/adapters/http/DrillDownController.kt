package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.contract.api.DrillDownApi
import org.springframework.web.bind.annotation.RestController

/**
 * The drill-down operation of the aggregation contract (TPL-08). It answers 501 until the task named for it in
 * `operations.tsv` overrides its method.
 */
@RestController
open class DrillDownController : DrillDownApi
