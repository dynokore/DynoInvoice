package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.contract.api.TransactionsApi
import org.springframework.web.bind.annotation.RestController

/**
 * The submission-list, transaction and replay operations of the submission contract (TPL-08). Every operation
 * answers 501 until the task named for it in `operations.tsv` overrides its method.
 */
@RestController
open class TransactionsController : TransactionsApi
