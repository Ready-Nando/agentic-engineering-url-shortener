package com.example.sdlc.engine;

import java.util.function.Function;

/**
 * A deterministic entry or exit condition. Entry gates decide whether a task may start; exit gates decide
 * whether an attempt's result is accepted. Gates may ask for a human, but only engine code decides that.
 */
public interface Gate {

    String name();

    GateResult evaluate(GateContext context);

    static Gate of(String name, Function<GateContext, GateResult> evaluation) {
        return new Gate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GateResult evaluate(GateContext context) {
                return evaluation.apply(context);
            }
        };
    }
}
