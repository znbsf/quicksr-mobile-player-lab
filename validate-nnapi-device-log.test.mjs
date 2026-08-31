import assert from "node:assert/strict";
import test from "node:test";

import { analyzeNnapiDeviceLog } from "./validate-nnapi-device-log.mjs";

function analyze(text) {
  return analyzeNnapiDeviceLog({
    logBytes: Buffer.from(text, "utf8"),
    logFileName: "cropped-device.log",
    generatedAt: "fixture",
  });
}

test("current MIUI freeze and nnapi-reference evidence fails with both causes", () => {
  const result = analyze(`
08-30 21:19:33.059 I onnxruntime: [NnapiExecutionProvider] GetCapability
08-30 21:19:33.087 I ExecutionPlan: compilation finished successfully on nnapi-reference
08-30 21:19:33.094 I ExecutionPlan: compilation finished successfully on nnapi-reference
08-30 21:19:35.770 D GreezeManager: FZ uid = 10274 reason =check binder success !
`);

  assert.equal(result.status, "FAIL");
  assert.equal(result.runCompletion, "environment-timeout");
  assert.equal(result.claims.hardwareAcceleratorClaimAllowed, false);
  assert.equal(result.observations.nnapiReferenceTokenCount, 2);
  assert.equal(result.observations.environmentInterrupted, true);
  assert.ok(
    result.failures.some(
      (item) => item.code === "nnapi-reference-cpu-route-observed",
    ),
  );
  assert.ok(
    result.failures.some((item) => item.code === "environment-timeout"),
  );
});

test("nnapi-reference fails even when the run is not frozen", () => {
  const result = analyze(`
I onnxruntime: NnapiExecutionProvider session initialized
I ExecutionPlan: compilation finished successfully on nnapi-reference
`);

  assert.equal(result.status, "FAIL");
  assert.equal(result.runCompletion, "not-established-by-device-log");
  assert.equal(result.claims.hardwareAcceleratorClaimAllowed, false);
});

test("ORT provider label without Android device routing cannot pass", () => {
  const result = analyze(`
{"cat":"Node","args":{"provider":"NnapiExecutionProvider"}}
`);

  assert.equal(result.status, "FAIL");
  assert.equal(result.claims.nnapiProviderLabelAloneSufficient, false);
  assert.ok(
    result.failures.some(
      (item) => item.code === "nnapi-compilation-device-not-proven",
    ),
  );
  assert.ok(
    result.failures.some(
      (item) => item.code === "known-hardware-accelerator-not-observed",
    ),
  );
});

test("known accelerator device plus ORT NNAPI evidence can pass the route gate", () => {
  const result = analyze(`
I onnxruntime: NnapiExecutionProvider session initialized
I ExecutionPlan: compilation finished successfully on qti-dsp
`);

  assert.equal(result.status, "PASS");
  assert.equal(result.claims.hardwareAcceleratorClaimAllowed, true);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.observations.devices, [
    {
      name: "qti-dsp",
      classification: "known-accelerator",
      compilationCount: 1,
    },
  ]);
});

test("mixed accelerator and nnapi-reference routing remains failed", () => {
  const result = analyze(`
I onnxruntime: NnapiExecutionProvider session initialized
I ExecutionPlan: compilation finished successfully on qti-dsp
I ExecutionPlan: compilation finished successfully on nnapi-reference
`);

  assert.equal(result.status, "FAIL");
  assert.equal(result.claims.hardwareAcceleratorClaimAllowed, false);
  assert.ok(
    result.failures.some(
      (item) => item.code === "nnapi-reference-cpu-route-observed",
    ),
  );
});

test("unknown non-reference device stays fail-closed", () => {
  const result = analyze(`
I onnxruntime: NnapiExecutionProvider session initialized
I ExecutionPlan: compilation finished successfully on vendor-default
`);

  assert.equal(result.status, "FAIL");
  assert.equal(result.claims.hardwareAcceleratorClaimAllowed, false);
  assert.ok(
    result.failures.some(
      (item) => item.code === "nnapi-compilation-device-unclassified",
    ),
  );
});
