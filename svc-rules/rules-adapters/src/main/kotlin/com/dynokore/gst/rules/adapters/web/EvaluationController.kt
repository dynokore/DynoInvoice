package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.contract.api.EvaluationApi
import org.springframework.web.bind.annotation.RestController

/**
 * The evaluation API of the rules contract (TPL-08). Every operation answers 501 until the task that implements it
 * (operations.tsv) overrides it: the generated interface's default is the 501 stub, so a contract operation is
 * never missing from the running service and never silently half-done.
 */
@RestController
class EvaluationController : EvaluationApi
