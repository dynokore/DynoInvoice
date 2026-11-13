package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore

/** The head record kept in memory, for the tests that exercise a publisher without a database. */
class InMemoryAuthorityHead : AuthorityHeadStore {
    private var value: Long? = null
    val advanced = mutableListOf<Long>()

    override fun advance(version: Long) {
        advanced += version
        value = maxOf(value ?: 0, version)
    }

    override fun current(): Long? = value
}
