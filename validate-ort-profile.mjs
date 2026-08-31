#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const SCHEMA_VERSION = "1.0.0";

const TARGET_PROVIDER_BY_BACKEND = new Map([
  ["CPU", "CPUExecutionProvider"],
  ["XNNPACK", "XnnpackExecutionProvider"],
  ["XNNPACK_HYBRID", "XnnpackExecutionProvider"],
  ["XNNPACK_CORE_STRICT", "XnnpackExecutionProvider"],
  ["XNNPACK_CORE_HYBRID", "XnnpackExecutionProvider"],
  ["NNAPI", "NnapiExecutionProvider"],
  ["NNAPI_HYBRID", "NnapiExecutionProvider"],
  ["NNAPI_DCR_STRICT", "NnapiExecutionProvider"],
  ["NNAPI_DCR_HYBRID", "NnapiExecutionProvider"],
  ["QNN_HTP_PROBE", "QNNExecutionProvider"],
  ["QNN_HTP_DCR_STRICT", "QNNExecutionProvider"],
  ["QNN_HTP_DCR_DIAGNOSTIC", "QNNExecutionProvider"],
]);

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function nonNegativeInteger(value) {
  return Number.isInteger(value) && value >= 0;
}

function normalizeNodeName(value) {
  return value.endsWith("_kernel_time")
    ? value.slice(0, -"_kernel_time".length)
    : value;
}

function failure(code, detail) {
  return detail === undefined ? { code } : { code, detail };
}

function emptyClaims(targetProvider, hybrid) {
  return {
    targetProvider,
    targetProviderObserved: false,
    targetProviderEventCount: 0,
    targetProviderUniqueNodeCount: 0,
    nonTargetProviderObserved: false,
    hybrid,
    assignmentClassification: "not-verified",
    fullGraphClaimAllowed: false,
    hardwareAcceleratorClaimAllowed: false,
    correctnessClaimAllowed: false,
    benchmarkClaimAllowed: false,
  };
}

/**
 * Validate and summarize one ORT profile referenced by one device receipt.
 *
 * Integrity is checked before JSON parsing. A matching hash does not prove
 * provider assignment; it only establishes that the analyzed bytes are the
 * bytes named by the receipt.
 */
export function analyzeOrtProfile({
  receipt,
  receiptFileName,
  profileBytes,
  profileFileName,
  generatedAt = new Date().toISOString(),
}) {
  const failures = [];
  const warnings = [];
  const safeReceiptName = basename(String(receiptFileName ?? "receipt.json"));
  const safeProfileName = basename(String(profileFileName ?? "profile.json"));
  const actualBytes = Buffer.isBuffer(profileBytes)
    ? profileBytes
    : Buffer.from(profileBytes ?? []);
  const actualSha256 = sha256(actualBytes);

  const backendRequested = isObject(receipt)
    ? receipt.backendRequested
    : undefined;
  const targetProvider = TARGET_PROVIDER_BY_BACKEND.get(backendRequested) ?? null;
  const hybrid =
    typeof backendRequested === "string" &&
    (backendRequested.endsWith("_HYBRID") ||
      (targetProvider !== null &&
        targetProvider !== "CPUExecutionProvider" &&
        receipt?.cpuEpFallbackDisabled === false));

  const result = {
    schemaVersion: SCHEMA_VERSION,
    generatedAt,
    status: "FAIL",
    scope: "ort-profile-node-events",
    receipt: {
      file: safeReceiptName,
      runId: isObject(receipt) ? receipt.runId ?? null : null,
      backendRequested: backendRequested ?? null,
      status: isObject(receipt) ? receipt.status ?? null : null,
      cpuEpFallbackDisabled: isObject(receipt)
        ? receipt.cpuEpFallbackDisabled ?? null
        : null,
    },
    profile: {
      file: safeProfileName,
      receiptDeclaredFile: null,
      expectedBytes: null,
      actualBytes: actualBytes.length,
      expectedSha256: null,
      actualSha256,
      integrityVerified: false,
      parsed: false,
    },
    runCoverage: {
      expectedModelRunEvents: null,
      actualModelRunEvents: null,
      matchesReceipt: false,
    },
    nodeEvents: {
      totalEventCount: 0,
      uniqueNodeCount: 0,
      providers: [],
      nodes: [],
    },
    claims: emptyClaims(targetProvider, hybrid),
    failures,
    warnings,
  };

  if (!isObject(receipt)) {
    failures.push(failure("receipt-not-an-object"));
    return result;
  }

  if (receipt.status !== "PASS") {
    failures.push(failure("receipt-status-not-pass", receipt.status ?? null));
  }
  if (receipt.profilingEnabled !== true) {
    failures.push(failure("receipt-profiling-not-enabled"));
  }
  if (targetProvider === null) {
    failures.push(failure("unsupported-backend", backendRequested ?? null));
  }

  const artifact = receipt.profilingArtifact;
  if (!isObject(artifact)) {
    failures.push(failure("receipt-profiling-artifact-missing"));
    return result;
  }

  const expectedFile =
    typeof artifact.file === "string" ? basename(artifact.file) : null;
  const expectedBytes = artifact.bytes;
  const expectedSha256 =
    typeof artifact.sha256 === "string"
      ? artifact.sha256.toLowerCase()
      : null;

  result.profile.receiptDeclaredFile = expectedFile;
  result.profile.expectedBytes = expectedBytes ?? null;
  result.profile.expectedSha256 = expectedSha256;

  if (expectedFile === null || expectedFile !== safeProfileName) {
    failures.push(
      failure("profile-file-name-mismatch", {
        expected: expectedFile,
        actual: safeProfileName,
      }),
    );
  }
  if (!nonNegativeInteger(expectedBytes) || expectedBytes !== actualBytes.length) {
    failures.push(
      failure("profile-byte-count-mismatch", {
        expected: nonNegativeInteger(expectedBytes) ? expectedBytes : null,
        actual: actualBytes.length,
      }),
    );
  }
  if (
    expectedSha256 === null ||
    !/^[0-9a-f]{64}$/.test(expectedSha256) ||
    expectedSha256 !== actualSha256
  ) {
    failures.push(
      failure("profile-sha256-mismatch", {
        expected:
          expectedSha256 !== null && /^[0-9a-f]{64}$/.test(expectedSha256)
            ? expectedSha256
            : null,
        actual: actualSha256,
      }),
    );
  }

  const integrityFailureCodes = new Set([
    "profile-file-name-mismatch",
    "profile-byte-count-mismatch",
    "profile-sha256-mismatch",
  ]);
  if (failures.some((item) => integrityFailureCodes.has(item.code))) {
    return result;
  }
  result.profile.integrityVerified = true;

  let events;
  try {
    const text = actualBytes.toString("utf8").replace(/^\uFEFF/, "");
    events = JSON.parse(text);
  } catch {
    failures.push(failure("profile-json-invalid"));
    return result;
  }
  if (!Array.isArray(events)) {
    failures.push(failure("profile-root-not-array"));
    return result;
  }
  result.profile.parsed = true;

  const warmupSucceeded = receipt.warmup?.succeeded;
  const measuredSucceeded = receipt.measured?.succeeded;
  if (
    !nonNegativeInteger(warmupSucceeded) ||
    !nonNegativeInteger(measuredSucceeded)
  ) {
    failures.push(failure("receipt-run-counts-invalid"));
  } else {
    result.runCoverage.expectedModelRunEvents =
      warmupSucceeded + measuredSucceeded;
  }

  const modelRunEvents = events.filter(
    (event) =>
      isObject(event) && event.cat === "Session" && event.name === "model_run",
  );
  result.runCoverage.actualModelRunEvents = modelRunEvents.length;
  if (
    result.runCoverage.expectedModelRunEvents !== null &&
    result.runCoverage.expectedModelRunEvents === modelRunEvents.length
  ) {
    result.runCoverage.matchesReceipt = true;
  } else {
    failures.push(
      failure("profile-model-run-count-mismatch", {
        expected: result.runCoverage.expectedModelRunEvents,
        actual: modelRunEvents.length,
      }),
    );
  }

  const rawNodeEvents = events.filter(
    (event) => isObject(event) && event.cat === "Node",
  );
  if (rawNodeEvents.length === 0) {
    failures.push(failure("profile-node-events-empty"));
  }

  const nodeMap = new Map();
  let malformedNodeEventCount = 0;
  for (const event of rawNodeEvents) {
    const args = event.args;
    const provider = isObject(args) ? args.provider : undefined;
    const opName = isObject(args) ? args.op_name : undefined;
    const eventName = event.name;
    if (
      typeof provider !== "string" ||
      provider.length === 0 ||
      typeof opName !== "string" ||
      opName.length === 0 ||
      typeof eventName !== "string" ||
      eventName.length === 0
    ) {
      malformedNodeEventCount += 1;
      continue;
    }

    const nodeName = normalizeNodeName(eventName);
    const nodeIndex =
      args.node_index === undefined || args.node_index === null
        ? null
        : String(args.node_index);
    const durationUs =
      typeof event.dur === "number" && Number.isFinite(event.dur) && event.dur >= 0
        ? event.dur
        : 0;
    const key = JSON.stringify([provider, opName, nodeName, nodeIndex]);
    let aggregate = nodeMap.get(key);
    if (aggregate === undefined) {
      aggregate = {
        provider,
        opName,
        nodeName,
        nodeIndex,
        eventCount: 0,
        totalDurationUs: 0,
        minDurationUs: null,
        maxDurationUs: null,
      };
      nodeMap.set(key, aggregate);
    }
    aggregate.eventCount += 1;
    aggregate.totalDurationUs += durationUs;
    aggregate.minDurationUs =
      aggregate.minDurationUs === null
        ? durationUs
        : Math.min(aggregate.minDurationUs, durationUs);
    aggregate.maxDurationUs =
      aggregate.maxDurationUs === null
        ? durationUs
        : Math.max(aggregate.maxDurationUs, durationUs);
  }

  if (malformedNodeEventCount > 0) {
    failures.push(
      failure("profile-node-events-malformed", {
        count: malformedNodeEventCount,
      }),
    );
  }

  const nodes = [...nodeMap.values()].sort(
    (left, right) =>
      left.provider.localeCompare(right.provider) ||
      left.nodeName.localeCompare(right.nodeName) ||
      left.opName.localeCompare(right.opName),
  );
  const providerMap = new Map();
  for (const node of nodes) {
    let provider = providerMap.get(node.provider);
    if (provider === undefined) {
      provider = {
        provider: node.provider,
        eventCount: 0,
        uniqueNodeCount: 0,
        totalDurationUs: 0,
        opNames: new Set(),
        nodeNames: [],
      };
      providerMap.set(node.provider, provider);
    }
    provider.eventCount += node.eventCount;
    provider.uniqueNodeCount += 1;
    provider.totalDurationUs += node.totalDurationUs;
    provider.opNames.add(node.opName);
    provider.nodeNames.push(node.nodeName);
  }

  const providers = [...providerMap.values()]
    .map((provider) => ({
      provider: provider.provider,
      eventCount: provider.eventCount,
      uniqueNodeCount: provider.uniqueNodeCount,
      totalDurationUs: provider.totalDurationUs,
      opNames: [...provider.opNames].sort(),
      nodeNames: provider.nodeNames.sort(),
    }))
    .sort((left, right) => left.provider.localeCompare(right.provider));

  result.nodeEvents.totalEventCount = nodes.reduce(
    (total, node) => total + node.eventCount,
    0,
  );
  result.nodeEvents.uniqueNodeCount = nodes.length;
  result.nodeEvents.providers = providers;
  result.nodeEvents.nodes = nodes;

  const targetSummary = providers.find(
    (provider) => provider.provider === targetProvider,
  );
  const nonTargetSummaries = providers.filter(
    (provider) => provider.provider !== targetProvider,
  );
  const targetEventCount = targetSummary?.eventCount ?? 0;
  const targetUniqueNodeCount = targetSummary?.uniqueNodeCount ?? 0;
  const targetObserved = targetUniqueNodeCount > 0;
  const nonTargetObserved = nonTargetSummaries.length > 0;

  result.claims.targetProviderObserved = targetObserved;
  result.claims.targetProviderEventCount = targetEventCount;
  result.claims.targetProviderUniqueNodeCount = targetUniqueNodeCount;
  result.claims.nonTargetProviderObserved = nonTargetObserved;

  if (!targetObserved) {
    failures.push(
      failure("target-provider-node-count-zero", {
        targetProvider,
      }),
    );
    result.claims.assignmentClassification =
      "target-provider-not-observed";
  } else if (hybrid && nonTargetObserved) {
    result.claims.assignmentClassification = "hybrid-partial";
  } else if (hybrid) {
    result.claims.assignmentClassification =
      "hybrid-target-observed-no-full-graph-claim";
    warnings.push(
      failure("hybrid-mode-forbids-full-graph-claim-even-with-target-only-events"),
    );
  } else if (nonTargetObserved) {
    result.claims.assignmentClassification = "mixed-provider";
  } else {
    result.claims.assignmentClassification =
      "target-provider-only-at-ort-node-event-level";
  }

  result.claims.fullGraphClaimAllowed =
    targetProvider !== "CPUExecutionProvider" &&
    !hybrid &&
    targetObserved &&
    !nonTargetObserved &&
    receipt.cpuEpFallbackDisabled === true;

  if (hybrid && result.claims.fullGraphClaimAllowed) {
    failures.push(failure("internal-hybrid-full-graph-claim-violation"));
    result.claims.fullGraphClaimAllowed = false;
  }

  result.status = failures.length === 0 ? "PASS" : "FAIL";
  return result;
}

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith("--") || index + 1 >= argv.length) {
      throw new Error("Expected --receipt, --profile, and optional --output arguments");
    }
    values[key.slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!values.receipt || !values.profile) {
    throw new Error("Both --receipt and --profile are required");
  }
  return values;
}

function writeJsonAtomic(outputPath, value) {
  const resolved = resolve(outputPath);
  const temporary = `${resolved}.tmp-${process.pid}-${Date.now()}`;
  try {
    writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, {
      encoding: "utf8",
      flag: "wx",
    });
    renameSync(temporary, resolved);
  } finally {
    try {
      unlinkSync(temporary);
    } catch {
      // The successful rename removes the temporary path.
    }
  }
}

function main(argv) {
  const args = parseArgs(argv);
  const receiptPath = resolve(args.receipt);
  const profilePath = resolve(args.profile);
  const receipt = JSON.parse(readFileSync(receiptPath, "utf8"));
  const profileBytes = readFileSync(profilePath);
  const result = analyzeOrtProfile({
    receipt,
    receiptFileName: basename(receiptPath),
    profileBytes,
    profileFileName: basename(profilePath),
  });

  if (args.output) {
    writeJsonAtomic(args.output, result);
  } else {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  }
  process.exitCode = result.status === "PASS" ? 0 : 1;
}

const isMain =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isMain) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(
      `ORT profile validation could not start: ${error instanceof Error ? error.message : "unknown error"}\n`,
    );
    process.exitCode = 2;
  }
}
