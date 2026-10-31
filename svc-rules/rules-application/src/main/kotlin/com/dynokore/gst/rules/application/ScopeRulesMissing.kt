package com.dynokore.gst.rules.application

/** No scope rules are configured, so no verdict can be given; the caller never receives a default one. */
class ScopeRulesMissing : RuntimeException("no ScopeVerdictPort is configured")
