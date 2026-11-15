// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoneFormationTest {
    private final List<String> placed = new ArrayList<>();
    private final StoneFormation.Placer placer = (x, y, z, obsidian) -> {
        placed.add((obsidian ? "obsidian " : "cobblestone ") + x + "," + y + "," + z);
        return true;
    };

    @Test
    void sixtyFourCooledParticlesMakeObsidian() {
        StoneFormation stone = new StoneFormation();
        for (int i = 0; i < 63; i++) {
            stone.add(5, 6, 7, 0, placer);
        }
        assertEquals(List.of(), placed);
        assertEquals(63, stone.progressAt(5, 6, 7));
        stone.add(5, 6, 7, 0, placer);
        assertEquals(List.of("obsidian 5,6,7"), placed);
        assertEquals(0, stone.size());
    }

    @Test
    void cooledParticlesJoinTheBiggestFormationNearby() {
        StoneFormation stone = new StoneFormation();
        stone.add(0, 0, 0, 0, placer);
        stone.add(0, 0, 0, 0, placer);
        // Diagonal neighbours join block (0, 0, 0) instead of starting their own.
        stone.add(1, 1, 1, 0, placer);
        stone.add(-1, 0, 1, 0, placer);
        assertEquals(4, stone.progressAt(0, 0, 0));
        assertEquals(1, stone.size());
        // Two blocks away is too far.
        stone.add(2, 0, 0, 0, placer);
        assertEquals(2, stone.size());
    }

    @Test
    void stalledFormationsBecomeObsidianCobblestoneOrSteam() {
        StoneFormation stone = new StoneFormation();
        for (int i = 0; i < StoneFormation.COBBLE_MINIMUM; i++) {
            stone.add(0, 0, 0, 10, placer);
        }
        for (int i = 0; i < StoneFormation.STALLED_OBSIDIAN_MINIMUM; i++) {
            stone.add(20, 0, 0, 10, placer);
        }
        stone.add(10, 0, 0, 10, placer);
        stone.finishStalled(10 + StoneFormation.STALL_TICKS - 1, placer);
        assertEquals(3, stone.size());

        stone.finishStalled(10 + StoneFormation.STALL_TICKS, placer);
        placed.sort(null);
        assertEquals(List.of("cobblestone 0,0,0", "obsidian 20,0,0"), placed);
        assertEquals(0, stone.size());
    }
}
