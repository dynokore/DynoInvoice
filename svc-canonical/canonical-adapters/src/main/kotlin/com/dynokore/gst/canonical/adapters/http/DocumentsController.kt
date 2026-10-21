package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.contract.api.DocumentsApi
import org.springframework.web.bind.annotation.RestController

/**
 * The document operations of the canonical contract (TPL-08). Every operation answers 501 until the task named for
 * it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class DocumentsController : DocumentsApi
