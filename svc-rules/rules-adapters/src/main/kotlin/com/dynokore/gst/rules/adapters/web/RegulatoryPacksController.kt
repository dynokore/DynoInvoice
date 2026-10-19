package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.contract.api.RegulatoryPacksApi
import org.springframework.web.bind.annotation.RestController

/** The regulatory pack API of the rules contract (TPL-08); 501 until its task overrides an operation. */
@RestController
class RegulatoryPacksController : RegulatoryPacksApi
