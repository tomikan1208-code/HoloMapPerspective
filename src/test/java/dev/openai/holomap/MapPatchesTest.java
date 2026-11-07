package dev.openai.holomap;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MapPatchesTest {
    @Test void sendsInitialMapAndOnlyTheChangedRectangleAfterwards() {
        MapPatches stream=new MapPatches(List.of(new MapPatches.Tile(7,"SOUTH",1,1,0,0)));
        byte[] frame=new byte[16384];
        assertEquals(16384,stream.update(Map.of("SOUTH",frame)).getFirst().pixels().length);
        assertTrue(stream.update(Map.of("SOUTH",frame)).isEmpty());
        frame[12*128+17]=51;
        MapPatches.Patch change=stream.update(Map.of("SOUTH",frame)).getFirst();
        assertEquals(17,change.x());assertEquals(12,change.y());
        assertEquals(1,change.width());assertEquals(1,change.height());assertArrayEquals(new byte[]{51},change.pixels());
        frame[12*128+17]=0;
        assertArrayEquals(new byte[]{0},stream.update(Map.of("SOUTH",frame)).getFirst().pixels());
        stream.reset();assertEquals(16384,stream.update(Map.of("SOUTH",frame)).getFirst().pixels().length);
    }
    @Test void tileRowsMatchTheBottomToTopPlacementOfItemFrames() {
        MapPatches stream=new MapPatches(List.of(new MapPatches.Tile(1,"UP",2,2,1,0)));
        byte[] face=new byte[4*16384];face[128*256+128]=63;
        assertEquals(63,stream.update(Map.of("UP",face)).getFirst().pixels()[0]);
    }
}
