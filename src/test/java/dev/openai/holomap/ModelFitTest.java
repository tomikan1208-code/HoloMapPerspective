package dev.openai.holomap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelFitTest {
    @Test void tallCharacterUsesHeightOfTwoByFourByTwoDisplay() {
        assertEquals(3.28,ModelFit.size(2,4,2,.4,1,.25,.82),1e-9);
    }
    @Test void wideHouseFitsWidthWithoutChangingItsAspectRatio() {
        double size=ModelFit.size(2,4,2,1,.4,.8,.82);
        assertEquals(1.64,size,1e-9);
        assertTrue(size*.4<=4*.82&&size*.8<=2*.82);
    }
    @Test void FlatModelDoesNotDivideByZero() {
        assertEquals(3.28,ModelFit.size(2,4,2,.2,1,0,.82),1e-9);
    }
}
