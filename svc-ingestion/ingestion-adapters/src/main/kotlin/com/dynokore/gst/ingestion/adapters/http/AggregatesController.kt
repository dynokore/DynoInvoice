package com.dynokore.gst.ingestion.adapters.http

import com.dynokore.gst.ingestion.contract.api.AggregatesApi
import org.springframework.web.bind.annotation.RestController

/**
 * The operations of one tag of the ingestion contract (TPL-08). Each answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class AggregatesController : AggregatesApi
