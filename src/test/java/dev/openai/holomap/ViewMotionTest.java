package dev.openai.holomap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewMotionTest {
    @Test void continuousMotionBetweenTicksAndStopsPredictingAfterOneTick() {
        long previous=1_000_000_000L,latest=previous+50_000_000L;
        double fraction=ViewMotion.fraction(previous,latest,latest+25_000_000L,.04);
        assertEquals(.5,fraction);
        assertEquals(1.3,ViewMotion.predict(1,1.2,fraction),1e-9);
        assertEquals(1,ViewMotion.fraction(previous,latest,latest+150_000_000L,.04));
    }
    @Test void teleportsAndStaleSamplesNeverOvershoot() {
        assertEquals(0,ViewMotion.fraction(0,50_000_000,75_000_000,9));
        assertEquals(0,ViewMotion.fraction(0,300_000_000,320_000_000,.04));
    }
}
