package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.contract.api.ReassignmentsApi
import org.springframework.web.bind.annotation.RestController

/**
 * The period-reassignment operations of the canonical contract (TPL-08). Every operation answers 501 until the task
 * named for it in `operations.tsv` overrides its method.
 */
@RestController
open class ReassignmentsController : ReassignmentsApi
