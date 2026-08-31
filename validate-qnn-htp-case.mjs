#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const SCHEMA_VERSION = "1.0.0";

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isSha256(value) {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

function parseJsonBytes(bytes, label, failures) {
  try {
    const value = JSON.parse(Buffer.from(bytes).toString("utf8").replace(/^\uFEFF/, ""));
    if (!isObject(value)) {
      failures.push({ code: `${label}-root-not-object` });
      return null;
    }
    return value;
  } catch {
    failures.push({ code: `${label}-json-invalid` });
    return null;
  }
}

function csvRows(bytes) {
  const rows = [];
  const lines = Buffer.from(bytes).toString("utf8").replace(/^\uFEFF/, "").split(/\r?\n/);
  for (const line of lines.slice(1)) {
    if (line.trim() === "") continue;
    const fields = line.split(",");
    if (fields.length >= 7) {
      rows.push({
        time: Number(fields[2]),
        unit: fields[3],
        identifier: fields[6],
      });
    }
  }
  return rows;
}

function countRows(rows, identifier) {
  return rows.filter((row) => row.identifier === identifier);
}

function gate(status, failures) {
  return { status: failures.length === 0 ? "PASS" : "FAIL", failures };
}

export function analyzeQnnHtpCase({
  receiptBytes,
  receiptFileName = "receipt.json",
  ortAnalysis,
  qnnCsvBytes,
  qnnCsvFileName = "qnn-profile.csv",
  qnnTraceBytes,
  qnnTraceFileName = "qnn_op_trace.json",
  goldenComparison = null,
  goldenValidation = null,
  buildSummary = null,
  generatedAt = new Date().toISOString(),
}) {
  const runtimeFailures = [];
  const placementFailures = [];
  const acceleratorFailures = [];
  const correctnessFailures = [];
  const buildFailures = [];
  const warnings = [];
  const receipt = parseJsonBytes(receiptBytes, "receipt", runtimeFailures);
  const trace = parseJsonBytes(qnnTraceBytes, "qnn-trace", acceleratorFailures);
  const rows = csvRows(qnnCsvBytes);

  const result = {
    schemaVersion: SCHEMA_VERSION,
    generatedAt,
    status: "FAIL",
    scope: "qnn-htp-strict-machine-evidence",
    receipt: {
      file: basename(receiptFileName),
      bytes: Buffer.byteLength(receiptBytes),
      sha256: sha256(receiptBytes),
      runId: receipt?.runId ?? null,
      buildId: receipt?.appBuild?.prototypeBuildId ?? null,
    },
    qnnCsv: {
      file: basename(qnnCsvFileName),
      bytes: Buffer.byteLength(qnnCsvBytes),
      sha256: sha256(qnnCsvBytes),
      counts: {},
    },
    qnnTrace: {
      file: basename(qnnTraceFileName),
      bytes: Buffer.byteLength(qnnTraceBytes),
      sha256: sha256(qnnTraceBytes),
      backendType: trace?.backend_type ?? null,
      htpArch: trace?.compilation_target?.htp_arch ?? null,
      summary: trace?.summary ?? null,
    },
    gates: {},
    claims: {
      htpExecutionClaimAllowed: false,
      correctnessClaimAllowed: false,
      buildIdentityLinked: false,
      strictMachineGatePassed: false,
      benchmarkClaimAllowed: false,
      fullFrameOrVideoClaimAllowed: false,
      humanReviewComplete: false,
    },
    warnings,
  };

  if (receipt !== null) {
    const qnn = receipt.qnnPlugin;
    if (receipt.status !== "PASS") runtimeFailures.push({ code: "receipt-status-not-pass" });
    if (receipt.backendRequested !== "QNN_HTP_DCR_STRICT") {
      runtimeFailures.push({ code: "backend-not-qnn-htp-strict" });
    }
    if (receipt.cpuEpFallbackDisabled !== true) {
      runtimeFailures.push({ code: "top-level-cpu-fallback-not-disabled" });
    }
    if (!isObject(qnn)) {
      runtimeFailures.push({ code: "qnn-plugin-evidence-missing" });
    } else {
      for (const [key, expected] of Object.entries({
        backendType: "htp",
        cpuEpFallbackDisabled: true,
        diagnosticOnly: false,
        registrationStatus: "PASS",
        npuSelectionStatus: "PASS",
        providerConfigurationStatus: "PASS",
        artifactCaptureStatus: "PASS",
        unregisterStatus: "PASS",
      })) {
        if (qnn[key] !== expected) runtimeFailures.push({ code: `qnn-${key}-mismatch` });
      }
      const options = qnn.providerOptions ?? {};
      if (options.backend_type !== "htp") runtimeFailures.push({ code: "provider-backend-not-htp" });
      if (options.offload_graph_io_quantization !== "0") {
        runtimeFailures.push({ code: "graph-io-offload-not-disabled" });
      }
      if (options.enable_htp_fp16_precision !== "0") {
        runtimeFailures.push({ code: "htp-fp16-not-explicitly-disabled" });
      }
      const npuDevices = Array.isArray(qnn.enumeratedEpDevices)
        ? qnn.enumeratedEpDevices.filter(
            (device) => device?.epName === "QNNExecutionProvider" && device?.hardwareType === "NPU",
          )
        : [];
      if (qnn.selectedNpuDeviceCount < 1 || npuDevices.length < 1) {
        runtimeFailures.push({ code: "qnn-npu-device-not-selected" });
      }
      const requiredLibraries = Array.isArray(qnn.nativeLibraryInventory)
        ? qnn.nativeLibraryInventory.filter((library) => library?.required === true)
        : [];
      if (
        requiredLibraries.length < 5 ||
        requiredLibraries.some(
          (library) => library.present !== true || library.bytes <= 0 || !isSha256(library.sha256),
        )
      ) {
        runtimeFailures.push({ code: "required-qnn-library-inventory-invalid" });
      }
      const csvArtifact = qnn.qnnProfilingArtifact;
      if (
        csvArtifact?.present !== true ||
        csvArtifact?.bytes !== Buffer.byteLength(qnnCsvBytes) ||
        csvArtifact?.sha256 !== sha256(qnnCsvBytes)
      ) {
        acceleratorFailures.push({ code: "qnn-csv-integrity-mismatch" });
      }
      const traceArtifact = Array.isArray(qnn.frameworkOpTraceArtifacts)
        ? qnn.frameworkOpTraceArtifacts.find((artifact) => artifact?.file === "qnn_op_trace.json")
        : null;
      if (
        traceArtifact?.present !== true ||
        traceArtifact?.bytes !== Buffer.byteLength(qnnTraceBytes) ||
        traceArtifact?.sha256 !== sha256(qnnTraceBytes)
      ) {
        acceleratorFailures.push({ code: "qnn-trace-integrity-mismatch" });
      }
    }

    const warmup = receipt.warmup ?? {};
    const measured = receipt.measured ?? {};
    if (warmup.planned !== 5 || warmup.succeeded !== 5) {
      runtimeFailures.push({ code: "warmup-contract-not-complete" });
    }
    if (measured.planned !== 30 || measured.succeeded !== 30) {
      runtimeFailures.push({ code: "measured-contract-not-complete" });
    }
    if (receipt.sessionCreated !== true || receipt.error !== null) {
      runtimeFailures.push({ code: "session-run-not-clean" });
    }

    if (!isObject(ortAnalysis) || ortAnalysis.status !== "PASS") {
      placementFailures.push({ code: "ort-analysis-not-pass" });
    } else {
      const claims = ortAnalysis.claims ?? {};
      if (
        ortAnalysis.receipt?.runId !== receipt.runId ||
        ortAnalysis.receipt?.backendRequested !== receipt.backendRequested
      ) {
        placementFailures.push({ code: "ort-analysis-receipt-linkage-mismatch" });
      }
      if (
        ortAnalysis.runCoverage?.expectedModelRunEvents !== 35 ||
        ortAnalysis.runCoverage?.actualModelRunEvents !== 35 ||
        ortAnalysis.runCoverage?.matchesReceipt !== true
      ) {
        placementFailures.push({ code: "ort-analysis-run-coverage-mismatch" });
      }
      if (
        claims.targetProvider !== "QNNExecutionProvider" ||
        claims.targetProviderObserved !== true ||
        claims.targetProviderEventCount !== 35 ||
        claims.nonTargetProviderObserved !== false ||
        claims.fullGraphClaimAllowed !== true
      ) {
        placementFailures.push({ code: "ort-analysis-not-qnn-only" });
      }
    }

    const receiptHash = sha256(receiptBytes);
    if (!isObject(goldenComparison) || goldenComparison.status !== "PASS") {
      correctnessFailures.push({ code: "golden-comparison-not-pass" });
    } else {
      if (
        goldenComparison.correctnessReferenceCompared !== true ||
        goldenComparison.backendRequested !== receipt.backendRequested ||
        goldenComparison.androidReceipt?.runId !== receipt.runId ||
        goldenComparison.androidReceipt?.sha256 !== receiptHash ||
        goldenComparison.metrics?.mismatchCount !== 0 ||
        goldenComparison.metrics?.nonfiniteCount !== 0
      ) {
        correctnessFailures.push({ code: "golden-comparison-linkage-or-metrics-invalid" });
      }
    }
    if (!isObject(goldenValidation) || goldenValidation.status !== "PASS") {
      correctnessFailures.push({ code: "independent-golden-validation-not-pass" });
    }

    const buildId = receipt.appBuild?.prototypeBuildId;
    if (typeof buildId !== "string" || buildId === "manual-unlinked" || buildId === "") {
      buildFailures.push({ code: "receipt-build-id-not-linked" });
    }
    if (!isObject(buildSummary) || buildSummary.status !== "PASS") {
      buildFailures.push({ code: "build-summary-not-pass" });
    } else if (
      buildSummary.runId !== buildId ||
      buildSummary.appBuildIdentity?.prototypeBuildId !== buildId ||
      buildSummary.appBuildIdentity?.matchesEvidenceRunId !== true ||
      buildSummary.appBuildIdentity?.sourceIdentitySha256 !== receipt.appBuild?.sourceIdentitySha256
    ) {
      buildFailures.push({ code: "build-summary-receipt-linkage-mismatch" });
    }
  }

  const expectedRuns = receipt?.warmup?.succeeded + receipt?.measured?.succeeded;
  const csvContracts = [
    ["qnnExecute", "QNN (execute) time", "US"],
    ["rpcExecute", "RPC (execute) time", "US"],
    ["qnnAcceleratorExecute", "QNN accelerator (execute) time", "US"],
    ["acceleratorCycles", "Accelerator (execute) time (cycles)", "CYCLES"],
    ["hvxThreads", "Number of HVX threads used", "COUNT"],
  ];
  for (const [key, identifier, unit] of csvContracts) {
    const matching = countRows(rows, identifier);
    result.qnnCsv.counts[key] = matching.length;
    if (!Number.isInteger(expectedRuns) || matching.length !== expectedRuns) {
      acceleratorFailures.push({ code: `qnn-csv-${key}-count-mismatch` });
    }
    if (matching.some((row) => row.unit !== unit || !Number.isFinite(row.time) || row.time <= 0)) {
      acceleratorFailures.push({ code: `qnn-csv-${key}-nonpositive-or-unit-mismatch` });
    }
  }

  if (trace !== null) {
    const summary = trace.summary ?? {};
    if (trace.backend_type !== "htp" || trace.compilation_target?.htp_arch !== "V73") {
      acceleratorFailures.push({ code: "qnn-trace-not-htp-v73" });
    }
    if (
      !Number.isInteger(summary.total_onnx_nodes) ||
      summary.total_onnx_nodes <= 0 ||
      summary.qnn_subgraphs !== 1 ||
      summary.supported_nodes !== summary.total_onnx_nodes ||
      summary.total_qnn_ops !== summary.total_onnx_nodes ||
      summary.unsupported_nodes !== 0 ||
      !Array.isArray(trace.unsupported_nodes) ||
      trace.unsupported_nodes.length !== 0
    ) {
      acceleratorFailures.push({ code: "qnn-trace-support-coverage-incomplete" });
    }
  }

  result.gates.runtimeContract = gate("runtime", runtimeFailures);
  result.gates.ortPlacement = gate("placement", placementFailures);
  result.gates.qnnAcceleratorTrace = gate("accelerator", acceleratorFailures);
  result.gates.goldenCorrectness = gate("correctness", correctnessFailures);
  result.gates.buildLinkage = gate("build", buildFailures);
  result.claims.htpExecutionClaimAllowed =
    runtimeFailures.length === 0 && placementFailures.length === 0 && acceleratorFailures.length === 0;
  result.claims.correctnessClaimAllowed = correctnessFailures.length === 0;
  result.claims.buildIdentityLinked = buildFailures.length === 0;
  result.claims.strictMachineGatePassed =
    result.claims.htpExecutionClaimAllowed &&
    result.claims.correctnessClaimAllowed &&
    result.claims.buildIdentityLinked;
  result.status = result.claims.strictMachineGatePassed ? "PASS" : "FAIL";
  if (receipt?.profilingEnabled === true) {
    warnings.push("profiling-enabled-timing-is-diagnostic-only");
  }
  warnings.push("64x64-synthetic-probe-does-not-establish-full-frame-or-video-performance");
  warnings.push("machine-evidence-does-not-complete-human-review");
  return result;
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) throw new Error("invalid CLI arguments");
    args[key.slice(2)] = value;
  }
  return args;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8").replace(/^\uFEFF/, ""));
}

function writeAtomic(path, value) {
  const target = resolve(path);
  const temporary = resolve(dirname(target), `.${basename(target)}.${process.pid}.tmp`);
  writeFileSync(temporary, JSON.stringify(value, null, 2) + "\n", "utf8");
  try {
    renameSync(temporary, target);
  } catch (error) {
    try { unlinkSync(temporary); } catch {}
    throw error;
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  for (const required of ["receipt", "ort-analysis", "qnn-csv", "qnn-trace", "build-summary", "output"]) {
    if (!args[required]) throw new Error(`missing --${required}`);
  }
  const result = analyzeQnnHtpCase({
    receiptBytes: readFileSync(args.receipt),
    receiptFileName: args.receipt,
    ortAnalysis: readJson(args["ort-analysis"]),
    qnnCsvBytes: readFileSync(args["qnn-csv"]),
    qnnCsvFileName: args["qnn-csv"],
    qnnTraceBytes: readFileSync(args["qnn-trace"]),
    qnnTraceFileName: args["qnn-trace"],
    goldenComparison: args["golden-comparison"] ? readJson(args["golden-comparison"]) : null,
    goldenValidation: args["golden-validation"] ? readJson(args["golden-validation"]) : null,
    buildSummary: readJson(args["build-summary"]),
  });
  writeAtomic(args.output, result);
  if (result.status !== "PASS") process.exitCode = 1;
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1] ?? "")).href) {
  try {
    main();
  } catch (error) {
    console.error(`QNN HTP case validation failed: ${error.name}: ${error.message}`);
    process.exitCode = 1;
  }
}
