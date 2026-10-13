package org.jooq.impl;

import org.jooq.Context;

/**
 * A consumer's custom field declared in jOOQ's own package (FatJarTest, review of !61): it must be refused like
 * every consumer custom part, whatever its package. Its text holds nothing the rendered-statement check refuses, so
 * only the guard's custom-part check stands in its way.
 */
public final class SpoofField extends CustomField<Integer> {
    public SpoofField() {
        super(DSL.name("spoof_field"), SQLDataType.INTEGER);
    }

    @Override
    public void accept(Context<?> ctx) {
        ctx.sql("(select 1)");
    }
}
