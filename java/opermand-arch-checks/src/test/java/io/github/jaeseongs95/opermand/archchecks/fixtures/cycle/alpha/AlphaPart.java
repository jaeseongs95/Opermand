package io.github.jaeseongs95.opermand.archchecks.fixtures.cycle.alpha;

import io.github.jaeseongs95.opermand.archchecks.fixtures.cycle.beta.BetaPart;

/** Breaks A-CYCLE on purpose: alpha uses beta and beta uses alpha (RuleFixturesTest.a_cycle). */
public final class AlphaPart {

    public String name() {
        return "alpha:" + new BetaPart().label();
    }
}
