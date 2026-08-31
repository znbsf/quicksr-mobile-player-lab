package dev.aisystems.quicksrplayerlab;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class ProbeResult {
    private final JSONObject receipt;
    private final byte[] outputFloat32LittleEndian;

    private ProbeResult(JSONObject receipt, byte[] outputFloat32LittleEndian) {
        this.receipt = receipt;
        this.outputFloat32LittleEndian = outputFloat32LittleEndian;
    }

    static ProbeResult withOutput(JSONObject receipt, float[] outputValues) {
        if (outputValues == null) {
            return receiptOnly(receipt);
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(outputValues.length, Float.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : outputValues) {
            bytes.putFloat(value);
        }
        return new ProbeResult(receipt, bytes.array());
    }

    static ProbeResult receiptOnly(JSONObject receipt) {
        return new ProbeResult(receipt, null);
    }

    JSONObject receipt() {
        return receipt;
    }

    byte[] outputFloat32LittleEndian() {
        return outputFloat32LittleEndian == null
                ? null
                : Arrays.copyOf(outputFloat32LittleEndian, outputFloat32LittleEndian.length);
    }

    float[] outputFloat32Values() {
        if (outputFloat32LittleEndian == null) {
            return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(outputFloat32LittleEndian)
                .order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[outputFloat32LittleEndian.length / Float.BYTES];
        for (int index = 0; index < values.length; index++) {
            values[index] = bytes.getFloat();
        }
        return values;
    }
}
