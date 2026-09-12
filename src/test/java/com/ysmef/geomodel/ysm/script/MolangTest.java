package com.ysmef.geomodel.ysm.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Molang evaluator regressions.
 *
 * Covers the two defects that made script evaluation non-deterministic or
 * silently inert:
 * - function calls must report their argument count, because the evaluator
 *   reuses one argument buffer per thread, so a short call used to read values
 *   left over from an earlier call site;
 * - the parser must survive hostile/deep input instead of killing the caller
 *   with a StackOverflowError, and must support the Bedrock syntax real model
 *   packs use (bare string operands, the {@code a ? b} ternary shorthand).
 */
class MolangTest {

    /** Records what the evaluator reports, and mirrors YSMPlayerAnimator's math.* handling. */
    private static final class RecordingEnv implements Molang.Env {
        final List<String> calls = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();

        @Override public double getVarById(int id) { return 0; }
        @Override public boolean hasVarById(int id) { return false; }
        @Override public void setVarById(int id, double value) { }
        @Override public double getQueryById(int id) { return 0; }
        @Override public double callStringFunction(String name, String[] args) { return 0; }

        @Override
        public double callFunction(String name, double[] args, int argCount) {
            calls.add(name);
            counts.add(argCount);
            switch (name) {
                case "math.min":
                    return argCount < 1 ? 0.0 : argCount < 2 ? args[0] : Math.min(args[0], args[1]);
                case "math.clamp":
                    return argCount < 3 ? 0.0 : Math.max(args[1], Math.min(args[2], args[0]));
                case "math.abs":
                    return argCount < 1 ? 0.0 : Math.abs(args[0]);
                case "math.pow":
                    return argCount < 2 ? 0.0 : Math.pow(args[0], args[1]);
                default:
                    return -1.0;
            }
        }
    }

    private static double eval(String src, Molang.Env env) {
        return Molang.compile(src).eval(env);
    }

    private static double eval(String src) {
        return eval(src, new RecordingEnv());
    }

    @Test
    void functionCallsReceiveExactArgumentCount() {
        RecordingEnv env = new RecordingEnv();
        eval("math.min(1, 2)", env);
        eval("math.abs(5)", env);
        eval("math.clamp(1, 2, 3)", env);
        eval("math.pow(2, 3)", env);

        assertEquals(List.of(2, 1, 3, 2), env.counts,
                "the evaluator must tell the Env how many arguments this call actually passed");
    }

    /**
     * The evaluator reuses its per-thread argument buffer, so a call with fewer
     * arguments than an earlier one must not read the leftovers.
     */
    @Test
    void shortCallsDoNotReadStaleArgumentSlots() {
        RecordingEnv env = new RecordingEnv();

        // fills slots 0..2 with 1, 2, 500
        assertEquals(2.0, eval("math.clamp(1, 2, 500)", env), 1e-9);
        // a one-argument min is the identity; a stale read would yield min(9, 2) == 2
        assertEquals(9.0, eval("math.min(9)", env), 1e-9,
                "math.min(9) must be 9, not a leftover operand from the previous call");

        // fills slots 0..1 with 3, 4
        assertEquals(81.0, eval("math.pow(3, 4)", env), 1e-9);
        // a stale read would yield pow(2, 4) == 16
        assertEquals(0.0, eval("math.pow(2)", env), 1e-9,
                "math.pow(2) has no exponent; it must not reuse the previous one");
    }

    @Test
    void evaluationIsIndependentOfCallOrder() {
        RecordingEnv env = new RecordingEnv();
        double before = eval("math.min(3)", env);
        eval("math.clamp(1, 2, 500)", env);
        double after = eval("math.min(3)", env);
        assertEquals(before, after, 1e-9);
    }

    /** Bedrock shorthand {@code a ? b}: b when a != 0, otherwise 0. */
    @Test
    void ternaryShorthandIsSupported() {
        assertEquals(7.0, eval("1 ? 7"), 1e-9);
        assertEquals(0.0, eval("0 ? 7"), 1e-9);
        assertEquals(7.0, eval("1 ? 7 : 9"), 1e-9);
        assertEquals(9.0, eval("0 ? 7 : 9"), 1e-9);
    }

    /** Bare string operands: number==string is false, same literal compares equal. */
    @Test
    void bareStringLiteralsCompareAsMarkers() {
        assertEquals(0.0, eval("5 == 'lit'"), 1e-9);
        assertEquals(1.0, eval("5 != 'lit'"), 1e-9);
        assertEquals(1.0, eval("'lit' == 'lit'"), 1e-9);
        assertEquals(0.0, eval("'lit' == 'other'"), 1e-9);
    }

    /**
     * A hostile model pack can nest thousands of parentheses; the parser must
     * report a compile failure and fall back to 0 rather than overflow the stack.
     */
    @Test
    void deeplyNestedInputDoesNotOverflowTheStack() {
        StringBuilder deep = new StringBuilder();
        deep.append("(".repeat(20000)).append('1').append(")".repeat(20000));
        assertTrue(deep.length() > 256);

        boolean[] reported = {false};
        Molang.setErrorReporter((src, err) -> reported[0] = true);
        try {
            assertEquals(0.0, eval(deep.toString()), 1e-9,
                    "an unnested-depth expression falls back to the safe 0");
            assertTrue(reported[0], "the compile failure must reach the error sink");
        } finally {
            Molang.setErrorReporter(null);
        }
    }

    @Test
    void deeplyNestedUnaryChainDoesNotOverflowTheStack() {
        assertEquals(0.0, eval("-".repeat(20000) + "1"), 1e-9);
    }

    @Test
    void ordinaryExpressionsAreUnaffected() {
        assertEquals(14.0, eval("2 + 3 * 4"), 1e-9);
        assertEquals(1.0, eval("2 > 1"), 1e-9);
        assertEquals(3.0, eval("1 ; 2 ; 3"), 1e-9);
    }
}
