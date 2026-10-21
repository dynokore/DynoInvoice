package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.contract.api.CertificationApi
import org.springframework.web.bind.annotation.RestController

/**
 * The certification operations of the evidence contract (TPL-08). Every operation answers 501 until the task named
 * for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class CertificationController : CertificationApi
