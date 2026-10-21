package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.contract.api.DespatchApi
import org.springframework.web.bind.annotation.RestController

/**
 * The `despatch` operations of the transport contract (TPL-08): submitPayload, submitPackage and sendNetworkDocument.
 * Every operation answers 501 until the task named for it in `operations.tsv` overrides its method: the generated
 * interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class DespatchController : DespatchApi
