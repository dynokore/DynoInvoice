package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.contract.api.ValidationApi
import org.springframework.web.bind.annotation.RestController

/**
 * The validation operations of the payload contract (TPL-08). Every operation answers 501 until the task named
 * for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class ValidationController : ValidationApi
