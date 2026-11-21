package org.floodguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RiskModelTests {
    private final RiskModel model=new RiskModel();

    @Test void scoreStaysWithinBounds() {
        assertEquals(0,model.score(0,0,10));
        assertEquals(100,model.score(1000,1,1));
    }

    @Test void configurableThresholdsDetermineBand() {
        assertEquals("LOW",model.level(39,40,70));
        assertEquals("MODERATE",model.level(40,40,70));
        assertEquals("HIGH",model.level(70,40,70));
        assertEquals("MODERATE",model.level(70,35,75));
    }
}
