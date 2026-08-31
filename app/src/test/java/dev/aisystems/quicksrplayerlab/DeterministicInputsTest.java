package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DeterministicInputsTest {
    @Test
    public void producesRepeatableNchwGradient() {
        float[] first = DeterministicInputs.rgbGradientNchw(3, 2);
        float[] second = DeterministicInputs.rgbGradientNchw(3, 2);
        assertArrayEquals(first, second, 0.0f);
        assertEquals(18, first.length);

        int plane = 6;
        assertEquals(0.0f, first[0], 0.0f);
        assertEquals(1.0f, first[2], 0.0f);
        assertEquals(1.0f, first[plane + 3], 0.0f);
        assertEquals(1.0f, first[(2 * plane) + 5], 0.0f);
    }

    @Test
    public void rejectsDegenerateDimensions() {
        assertThrows(IllegalArgumentException.class, () -> DeterministicInputs.rgbGradientNchw(1, 2));
        assertThrows(IllegalArgumentException.class, () -> DeterministicInputs.rgbGradientNchw(2, 1));
    }
}
