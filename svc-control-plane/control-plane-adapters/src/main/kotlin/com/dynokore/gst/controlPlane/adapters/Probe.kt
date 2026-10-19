package com.dynokore.gst.controlPlane.adapters
import org.springframework.jdbc.core.JdbcTemplate
class Probe(private val t: JdbcTemplate) { fun f() = t.execute("select 1") }
