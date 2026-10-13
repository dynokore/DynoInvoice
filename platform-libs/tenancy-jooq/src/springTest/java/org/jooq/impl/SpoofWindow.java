package org.jooq.impl;

import java.util.function.Function;
import org.jooq.Context;

/**
 * A consumer's subclass of jOOQ's internal window function base class, in jOOQ's own package (FatJarTest, review of
 * !61). It renders exactly like jOOQ's row_number(), OVER clause included, so only the exact-class check can tell it
 * from jOOQ's own RowNumber: its OVER part must be refused.
 */
public final class SpoofWindow extends AbstractWindowFunction<Integer, SpoofWindow> {
    public SpoofWindow() {
        super(DSL.name("row_number"), SQLDataType.INTEGER);
    }

    @Override
    public void accept(Context<?> ctx) {
        ctx.visit(DSL.name("row_number")).sql("()");
        acceptOverClause(ctx);
    }

    @Override
    SpoofWindow copyWindowFunction(Function<? super SpoofWindow, ? extends SpoofWindow> function) {
        return this;
    }
}
