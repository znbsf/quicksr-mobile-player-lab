package dev.aisystems.quicksrplayerlab;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DeviceSnapshot {
    private DeviceSnapshot() {
    }

    static JSONObject capture(Context context) {
        JSONObject snapshot = new JSONObject();
        JSONArray captureErrors = new JSONArray();

        try {
            snapshot.put("manufacturer", Build.MANUFACTURER);
            snapshot.put("brand", Build.BRAND);
            snapshot.put("model", Build.MODEL);
            snapshot.put("device", Build.DEVICE);
            snapshot.put("hardware", Build.HARDWARE);
            snapshot.put("androidRelease", Build.VERSION.RELEASE);
            snapshot.put("sdkInt", Build.VERSION.SDK_INT);
            snapshot.put("securityPatch", Build.VERSION.SECURITY_PATCH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                snapshot.put("socManufacturer", Build.SOC_MANUFACTURER);
                snapshot.put("socModel", Build.SOC_MODEL);
            } else {
                snapshot.put("socManufacturer", JSONObject.NULL);
                snapshot.put("socModel", JSONObject.NULL);
            }

            JSONArray abis = new JSONArray();
            for (String abi : Build.SUPPORTED_ABIS) {
                abis.put(abi);
            }
            snapshot.put("supportedAbis", abis);
        } catch (Exception error) {
            addCaptureError(captureErrors, "build-identity", error);
        }

        try {
            snapshot.put("processPssKb", Debug.getPss());
            snapshot.put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize());
            snapshot.put(
                    "javaHeapUsedBytes",
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            );

            ActivityManager activityManager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                snapshot.put("systemAvailableMemoryBytes", memoryInfo.availMem);
                snapshot.put("systemLowMemory", memoryInfo.lowMemory);
            }
        } catch (Exception error) {
            addCaptureError(captureErrors, "memory", error);
        }

        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int tenthsCelsius = battery.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE,
                        Integer.MIN_VALUE
                );
                if (tenthsCelsius != Integer.MIN_VALUE) {
                    snapshot.put("batteryTemperatureC", tenthsCelsius / 10.0);
                }
                snapshot.put("batteryLevel", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1));
                snapshot.put("batteryScale", battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
            }
        } catch (Exception error) {
            addCaptureError(captureErrors, "battery", error);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    snapshot.put("thermalStatus", powerManager.getCurrentThermalStatus());
                }
            }
        } catch (Exception error) {
            addCaptureError(captureErrors, "thermal", error);
        }

        try {
            snapshot.put("serialCaptured", false);
            snapshot.put("fingerprintCaptured", false);
            snapshot.put("captureErrors", captureErrors);
            snapshot.put("captureStatus", captureErrors.length() == 0 ? "complete" : "partial");
        } catch (JSONException ignored) {
            // Keys and values above are controlled. If serialization still fails, return the partial object.
        }
        return snapshot;
    }

    private static void addCaptureError(JSONArray errors, String component, Exception error) {
        JSONObject value = new JSONObject();
        try {
            value.put("component", component);
            value.put("type", error.getClass().getName());
            value.put("message", String.valueOf(error.getMessage()));
        } catch (JSONException ignored) {
            // Preserve at least an empty marker rather than making telemetry a run gate.
        }
        errors.put(value);
    }
}
