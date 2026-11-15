package com.dynokore.gst.consoleBff.application.session

/**
 * The case reference of an operator session, by the rule of `OperatorSessionRequest.caseId` in access-svc's
 * contract (coordinator ruling INV-Q-4534): 1 to 64 code points, at least one outside the caseId whitespace set,
 * no ':' and no ISO control character. Length counts code points, not UTF-16 units.
 */
object CaseReference {
    const val MAX_CODE_POINTS = 64

    private val WHITESPACE: Set<Int> =
        buildSet {
            add(0x0020)
            add(0x00A0)
            add(0x1680)
            addAll(0x2000..0x200A)
            add(0x2028)
            add(0x2029)
            add(0x202F)
            add(0x205F)
            add(0x3000)
            add(0xFEFF)
        }

    fun isValid(value: String?): Boolean {
        if (value == null) return false
        val points = value.codePoints().toArray()
        return points.size in 1..MAX_CODE_POINTS &&
            points.any { it !in WHITESPACE } &&
            points.none { it == ':'.code || Character.isISOControl(it) }
    }
}
