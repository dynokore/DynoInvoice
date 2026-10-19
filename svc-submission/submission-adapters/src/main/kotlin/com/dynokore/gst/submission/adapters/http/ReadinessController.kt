package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.contract.api.ReadinessApi
import org.springframework.web.bind.annotation.RestController

/**
 * The readiness and filing operations of the submission contract (TPL-08). Every operation answers 501 until the
 * task named for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
class ReadinessController : ReadinessApi
