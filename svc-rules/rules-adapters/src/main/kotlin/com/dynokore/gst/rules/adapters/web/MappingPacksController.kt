package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.contract.api.MappingPacksApi
import org.springframework.web.bind.annotation.RestController

/** The mapping pack authoring API of the rules contract (TPL-08); 501 until its task overrides an operation. */
@RestController
class MappingPacksController : MappingPacksApi
