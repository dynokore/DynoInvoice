package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.contract.api.AdministrationApi
import org.springframework.web.bind.annotation.RestController

/** The `administration` operations of the transport contract (TPL-08); each answers 501 until its task overrides it. */
@RestController
open class AdministrationController : AdministrationApi
