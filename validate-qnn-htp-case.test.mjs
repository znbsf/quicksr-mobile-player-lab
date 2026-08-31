import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { analyzeQnnHtpCase } from "./validate-qnn-htp-case.mjs";

const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");

function fixture() {
  const traceBytes = Buffer.from(JSON.stringify({
    backend_type: "htp",
    compilation_target: { htp_arch: "V73" },
    summary: {
      qnn_subgraphs: 1,
      supported_nodes: 11,
      total_onnx_nodes: 11,
      total_qnn_ops: 11,
      unsupported_nodes: 0,
    },
    unsupported_nodes: [],
  }));
  const identifiers = [
    ["QNN (execute) time", "US", 100],
    ["RPC (execute) time", "US", 90],
    ["QNN accelerator (execute) time", "US", 80],
    ["Accelerator (execute) time (cycles)", "CYCLES", 1000],
    ["Number of HVX threads used", "COUNT", 4],
  ];
  const csvLines = ["Msg Timestamp,Message,Time,Unit of Measurement,Timing Source,Event Level,Event Identifier,ONNX Source Ops"];
  for (let run = 0; run < 35; run += 1) {
    for (const [identifier, unit, value] of identifiers) {
      csvLines.push(`0,BACKEND,${value},${unit},BACKEND,ROOT,${identifier},`);
    }
  }
  const csvBytes = Buffer.from(csvLines.join("\n") + "\n");
  const receipt = {
    runId: "fixture-run",
    status: "PASS",
    backendRequested: "QNN_HTP_DCR_STRICT",
    cpuEpFallbackDisabled: true,
    profilingEnabled: true,
    sessionCreated: true,
    error: null,
    warmup: { planned: 5, succeeded: 5 },
    measured: { planned: 30, succeeded: 30 },
    appBuild: { prototypeBuildId: "fixture-build", sourceIdentitySha256: "a".repeat(64) },
    qnnPlugin: {
      backendType: "htp",
      cpuEpFallbackDisabled: true,
      diagnosticOnly: false,
      registrationStatus: "PASS",
      npuSelectionStatus: "PASS",
      providerConfigurationStatus: "PASS",
      artifactCaptureStatus: "PASS",
      unregisterStatus: "PASS",
      selectedNpuDeviceCount: 1,
      providerOptions: {
        backend_type: "htp",
        offload_graph_io_quantization: "0",
        enable_htp_fp16_precision: "0",
      },
      enumeratedEpDevices: [{ epName: "QNNExecutionProvider", hardwareType: "NPU" }],
      nativeLibraryInventory: Array.from({ length: 5 }, (_, index) => ({
        file: `lib-${index}.so`, required: true, present: true, bytes: 1, sha256: "b".repeat(64),
      })),
      qnnProfilingArtifact: { present: true, bytes: csvBytes.length, sha256: hash(csvBytes) },
      frameworkOpTraceArtifacts: [{
        file: "qnn_op_trace.json", present: true, bytes: traceBytes.length, sha256: hash(traceBytes),
      }],
    },
  };
  const receiptBytes = Buffer.from(JSON.stringify(receipt));
  const ortAnalysis = {
    status: "PASS",
    receipt: { runId: receipt.runId, backendRequested: receipt.backendRequested },
    runCoverage: { expectedModelRunEvents: 35, actualModelRunEvents: 35, matchesReceipt: true },
    claims: {
      targetProvider: "QNNExecutionProvider",
      targetProviderObserved: true,
      targetProviderEventCount: 35,
      nonTargetProviderObserved: false,
      fullGraphClaimAllowed: true,
    },
  };
  const goldenComparison = {
    status: "PASS",
    correctnessReferenceCompared: true,
    backendRequested: receipt.backendRequested,
    androidReceipt: { runId: receipt.runId, sha256: hash(receiptBytes) },
    metrics: { mismatchCount: 0, nonfiniteCount: 0 },
  };
  const goldenValidation = { status: "PASS" };
  const buildSummary = {
    status: "PASS",
    runId: "fixture-build",
    appBuildIdentity: {
      prototypeBuildId: "fixture-build",
      sourceIdentitySha256: "a".repeat(64),
      matchesEvidenceRunId: true,
    },
  };
  return { receipt, receiptBytes, ortAnalysis, csvBytes, traceBytes, goldenComparison, goldenValidation, buildSummary };
}

function analyze(value) {
  return analyzeQnnHtpCase({
    receiptBytes: value.receiptBytes,
    ortAnalysis: value.ortAnalysis,
    qnnCsvBytes: value.csvBytes,
    qnnTraceBytes: value.traceBytes,
    goldenComparison: value.goldenComparison,
    goldenValidation: value.goldenValidation,
    buildSummary: value.buildSummary,
    generatedAt: "fixture",
  });
}

test("complete strict QNN fixture passes every machine gate", () => {
  const result = analyze(fixture());
  assert.equal(result.status, "PASS");
  assert.equal(result.claims.htpExecutionClaimAllowed, true);
  assert.equal(result.claims.correctnessClaimAllowed, true);
  assert.equal(result.claims.buildIdentityLinked, true);
  assert.equal(result.claims.benchmarkClaimAllowed, false);
});

test("golden failure preserves HTP execution evidence but fails the aggregate gate", () => {
  const value = fixture();
  value.goldenComparison = { status: "FAIL" };
  value.goldenValidation = null;
  const result = analyze(value);
  assert.equal(result.status, "FAIL");
  assert.equal(result.claims.htpExecutionClaimAllowed, true);
  assert.equal(result.claims.correctnessClaimAllowed, false);
});

test("CPU fallback enabled invalidates the HTP execution claim", () => {
  const value = fixture();
  value.receipt.cpuEpFallbackDisabled = false;
  value.receiptBytes = Buffer.from(JSON.stringify(value.receipt));
  value.goldenComparison.androidReceipt.sha256 = hash(value.receiptBytes);
  const result = analyze(value);
  assert.equal(result.claims.htpExecutionClaimAllowed, false);
  assert.equal(result.gates.runtimeContract.status, "FAIL");
});

test("unsupported QNN nodes invalidate the accelerator trace gate", () => {
  const value = fixture();
  const trace = JSON.parse(value.traceBytes);
  trace.summary.supported_nodes = 10;
  trace.summary.unsupported_nodes = 1;
  trace.unsupported_nodes = [{ name: "fixture" }];
  value.traceBytes = Buffer.from(JSON.stringify(trace));
  value.receipt.qnnPlugin.frameworkOpTraceArtifacts[0].bytes = value.traceBytes.length;
  value.receipt.qnnPlugin.frameworkOpTraceArtifacts[0].sha256 = hash(value.traceBytes);
  value.receiptBytes = Buffer.from(JSON.stringify(value.receipt));
  value.goldenComparison.androidReceipt.sha256 = hash(value.receiptBytes);
  const result = analyze(value);
  assert.equal(result.claims.htpExecutionClaimAllowed, false);
  assert.equal(result.gates.qnnAcceleratorTrace.status, "FAIL");
});

test("missing one QNN execute row fails closed", () => {
  const value = fixture();
  const lines = value.csvBytes.toString("utf8").trimEnd().split("\n");
  const index = lines.findIndex((line) => line.includes(",QNN (execute) time,"));
  lines.splice(index, 1);
  value.csvBytes = Buffer.from(lines.join("\n") + "\n");
  value.receipt.qnnPlugin.qnnProfilingArtifact.bytes = value.csvBytes.length;
  value.receipt.qnnPlugin.qnnProfilingArtifact.sha256 = hash(value.csvBytes);
  value.receiptBytes = Buffer.from(JSON.stringify(value.receipt));
  value.goldenComparison.androidReceipt.sha256 = hash(value.receiptBytes);
  const result = analyze(value);
  assert.equal(result.claims.htpExecutionClaimAllowed, false);
  assert.equal(result.qnnCsv.counts.qnnExecute, 34);
});
