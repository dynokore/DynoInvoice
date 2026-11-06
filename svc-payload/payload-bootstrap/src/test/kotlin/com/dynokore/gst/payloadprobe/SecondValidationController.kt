package com.dynokore.gst.payloadprobe

import com.dynokore.gst.payload.contract.api.ValidationApi
import org.springframework.web.bind.annotation.RestController

/**
 * The probe of INV-Q-17264 A: a second `@RestController` implementing the generated `ValidationApi`. It lives outside
 * the packages the application scans, so only a test that registers it sees it.
 */
@RestController
open class SecondValidationController : ValidationApi
