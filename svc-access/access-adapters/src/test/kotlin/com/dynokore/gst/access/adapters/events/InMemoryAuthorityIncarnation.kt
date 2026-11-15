package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore

/** A fixed incarnation for the unit tests of the publishers (the store itself is proved over the database). */
class InMemoryAuthorityIncarnation(
    private var value: Long = 1L,
) : AuthorityIncarnationStore {
    override fun current(): Long = value

    override fun start(): Long = value

    override fun bump(): Long = ++value
}
