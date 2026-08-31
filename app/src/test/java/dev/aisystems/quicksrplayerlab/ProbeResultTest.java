package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public final class ProbeResultTest {
    @Test
    public void outputEncodingIsFloat32LittleEndian() {
        ProbeResult result = ProbeResult.withOutput(new JSONObject(), new float[]{1.0f, -2.5f});
        assertArrayEquals(
                new byte[]{0, 0, (byte) 0x80, 0x3f, 0, 0, 0x20, (byte) 0xc0},
                result.outputFloat32LittleEndian());
        assertArrayEquals(new float[]{1.0f, -2.5f}, result.outputFloat32Values(), 0.0f);
    }

    @Test
    public void missingOutputRemainsMissingForBothViews() {
        ProbeResult result = ProbeResult.receiptOnly(new JSONObject());
        assertNull(result.outputFloat32LittleEndian());
        assertNull(result.outputFloat32Values());
    }
}
