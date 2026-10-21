package com.dynokore.gst.masterdata.adapters.persistence

/** A column the schema declares NOT NULL, read from a generated record whose getters are nullable. */
internal fun <T : Any> T?.column(name: String): T =
    checkNotNull(this) { "column $name is null although it is NOT NULL" }
