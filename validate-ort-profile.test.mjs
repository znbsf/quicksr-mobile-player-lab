import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { analyzeOrtProfile } from "./validate-ort-profile.mjs";

function encoded(events) {
  return Buffer.from(JSON.stringify(events), "utf8");
}

function hash(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function receiptFor({
  bytes,
  backendRequested = "XNNPACK_HYBRID",
  file = "profile.json",
  sha256 = hash(bytes),
}) {
  return {
    runId: "fixture-run",
    status: "PASS",
    backendRequested,
    cpuEpFallbackDisabled:
      backendRequested.endsWith("_HYBRID") ||
      backendRequested.endsWith("_DIAGNOSTIC")
        ? false
        : true,
    profilingEnabled: true,
    profilingArtifact: {
      file,
      bytes: bytes.length,
      sha256,
    },
    warmup: { succeeded: 0 },
    measured: { succeeded: 1 },
  };
}

function modelRun() {
  return { cat: "Session", name: "model_run", args: {} };
}

function node(provider, opName, name, nodeIndex, dur = 10) {
  return {
    cat: "Node",
    name: `${name}_kernel_time`,
    dur,
    args: {
      provider,
      op_name: opName,
      node_index: String(nodeIndex),
    },
  };
}

test("hybrid trace passes only as partial assignment evidence", () => {
  const bytes = encoded([
    modelRun(),
    node("XnnpackExecutionProvider", "Conv", "xnn-conv", 1),
    node("CPUExecutionProvider", "DepthToSpace", "pixel-shuffle", 2),
  ]);
  const result = analyzeOrtProfile({
    receipt: receiptFor({ bytes }),
    receiptFileName: "receipt.json",
    profileBytes: bytes,
    profileFileName: "profile.json",
    generatedAt: "fixture",
  });

  assert.equal(result.status, "PASS");
  assert.equal(result.profile.integrityVerified, true);
  assert.equal(result.claims.targetProviderObserved, true);
  assert.equal(result.claims.targetProviderUniqueNodeCount, 1);
  assert.equal(result.claims.nonTargetProviderObserved, true);
  assert.equal(result.claims.assignmentClassification, "hybrid-partial");
  assert.equal(result.claims.fullGraphClaimAllowed, false);
});

test("zero target-provider nodes fails closed", () => {
  const bytes = encoded([
    modelRun(),
    node("CPUExecutionProvider", "FusedConv", "cpu-conv", 1),
  ]);
  const result = analyzeOrtProfile({
    receipt: receiptFor({ bytes }),
    receiptFileName: "receipt.json",
    profileBytes: bytes,
    profileFileName: "profile.json",
    generatedAt: "fixture",
  });

  assert.equal(result.status, "FAIL");
  assert.equal(result.claims.targetProviderObserved, false);
  assert.ok(
    result.failures.some(
      (item) => item.code === "target-provider-node-count-zero",
    ),
  );
});

test("hash mismatch stops before profile parsing", () => {
  const bytes = encoded([
    modelRun(),
    node("XnnpackExecutionProvider", "Conv", "xnn-conv", 1),
  ]);
  const result = analyzeOrtProfile({
    receipt: receiptFor({ bytes, sha256: "0".repeat(64) }),
    receiptFileName: "receipt.json",
    profileBytes: bytes,
    profileFileName: "profile.json",
    generatedAt: "fixture",
  });

  assert.equal(result.status, "FAIL");
  assert.equal(result.profile.integrityVerified, false);
  assert.equal(result.profile.parsed, false);
  assert.equal(result.nodeEvents.totalEventCount, 0);
  assert.ok(
    result.failures.some((item) => item.code === "profile-sha256-mismatch"),
  );
});

test("malformed Node event fails instead of silently dropping it", () => {
  const bytes = encoded([
    modelRun(),
    node("XnnpackExecutionProvider", "Conv", "xnn-conv", 1),
    {
      cat: "Node",
      name: "missing-provider_kernel_time",
      args: { op_name: "DepthToSpace" },
    },
  ]);
  const result = analyzeOrtProfile({
    receipt: receiptFor({ bytes }),
    receiptFileName: "receipt.json",
    profileBytes: bytes,
    profileFileName: "profile.json",
    generatedAt: "fixture",
  });

  assert.equal(result.status, "FAIL");
  assert.ok(
    result.failures.some(
      (item) => item.code === "profile-node-events-malformed",
    ),
  );
});

test("hybrid never permits a full-graph claim even with target-only events", () => {
  const bytes = encoded([
    modelRun(),
    node("XnnpackExecutionProvider", "Conv", "xnn-conv", 1),
  ]);
  const result = analyzeOrtProfile({
    receipt: receiptFor({ bytes }),
    receiptFileName: "receipt.json",
    profileBytes: bytes,
    profileFileName: "profile.json",
    generatedAt: "fixture",
  });

  assert.equal(result.status, "PASS");
  assert.equal(
    result.claims.assignmentClassification,
    "hybrid-target-observed-no-full-graph-claim",
  );
  assert.equal(result.claims.fullGraphClaimAllowed, false);
});

test("P2 derived-model backend names map to their execution providers", () => {
  const cases = [
    ["XNNPACK_CORE_STRICT", "XnnpackExecutionProvider", false],
    ["XNNPACK_CORE_HYBRID", "XnnpackExecutionProvider", true],
    ["NNAPI_DCR_STRICT", "NnapiExecutionProvider", false],
    ["NNAPI_DCR_HYBRID", "NnapiExecutionProvider", true],
    ["QNN_HTP_DCR_STRICT", "QNNExecutionProvider", false],
    ["QNN_HTP_DCR_DIAGNOSTIC", "QNNExecutionProvider", true],
  ];

  for (const [backendRequested, provider, hybrid] of cases) {
    const bytes = encoded([
      modelRun(),
      node(provider, "Conv", `${provider}-node`, 1),
    ]);
    const result = analyzeOrtProfile({
      receipt: receiptFor({ bytes, backendRequested }),
      receiptFileName: "receipt.json",
      profileBytes: bytes,
      profileFileName: "profile.json",
      generatedAt: "fixture",
    });

    assert.equal(result.status, "PASS", backendRequested);
    assert.equal(result.claims.targetProvider, provider, backendRequested);
    assert.equal(result.claims.targetProviderObserved, true, backendRequested);
    assert.equal(result.claims.hybrid, hybrid, backendRequested);
    assert.equal(
      result.claims.fullGraphClaimAllowed,
      !hybrid,
      backendRequested,
    );
  }
});
