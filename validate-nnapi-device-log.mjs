#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const SCHEMA_VERSION = "1.0.0";

// These names identify an accelerator class, not merely a vendor/default
// driver. Unknown non-reference devices stay fail-closed until classified.
const KNOWN_ACCELERATOR_DEVICE_PATTERN =
  /(?:^|[-_.])(gpu|dsp|npu|hta|htp)(?:$|[-_.])/i;

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function countMatches(text, pattern) {
  return [...text.matchAll(pattern)].length;
}

function failure(code, detail) {
  return detail === undefined ? { code } : { code, detail };
}

function classifyDevice(deviceName) {
  if (/^nnapi-reference$/i.test(deviceName)) {
    return "reference-cpu";
  }
  if (KNOWN_ACCELERATOR_DEVICE_PATTERN.test(deviceName)) {
    return "known-accelerator";
  }
  return "unclassified-non-reference";
}

/**
 * Gate a cropped Android log for an NNAPI hardware-accelerator claim.
 *
 * NnapiExecutionProvider is only an ORT provider label. Hardware is established
 * separately by Android's compilation-device line. Any nnapi-reference token,
 * an unclassified device, or an environment interruption fails closed.
 */
export function analyzeNnapiDeviceLog({
  logBytes,
  logFileName = "device.log",
  generatedAt = new Date().toISOString(),
}) {
  const bytes = Buffer.isBuffer(logBytes)
    ? logBytes
    : Buffer.from(logBytes ?? [], "utf8");
  const text = bytes.toString("utf8").replace(/^\uFEFF/, "");
  const failures = [];
  const warnings = [];

  const nnapiExecutionProviderMentionCount = countMatches(
    text,
    /\bNnapiExecutionProvider\b/gi,
  );
  const referenceTokenCount = countMatches(text, /\bnnapi-reference\b/gi);
  const showWhenLockedDeniedCount = countMatches(
    text,
    /Show when locked PermissionDenied/gi,
  );
  const freezeEventCount = countMatches(
    text,
    /(?:GreezeManager[^\r\n]*(?:ActionExecute:\s*frozen|\bfrozen\b|\bfreeze(?:d|ing)?\b|\bFZ\s+uid\b)|ActionExecute:\s*frozen)/gi,
  );
  const environmentInterrupted = freezeEventCount > 0;

  const deviceCounts = new Map();
  const compilationPattern =
    /compilation finished successfully on\s+([^\s\r\n]+)/gi;
  for (const match of text.matchAll(compilationPattern)) {
    const deviceName = match[1];
    deviceCounts.set(deviceName, (deviceCounts.get(deviceName) ?? 0) + 1);
  }

  const devices = [...deviceCounts.entries()]
    .map(([name, compilationCount]) => ({
      name,
      classification: classifyDevice(name),
      compilationCount,
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
  const referenceDevices = devices.filter(
    (item) => item.classification === "reference-cpu",
  );
  const knownAcceleratorDevices = devices.filter(
    (item) => item.classification === "known-accelerator",
  );
  const unclassifiedDevices = devices.filter(
    (item) => item.classification === "unclassified-non-reference",
  );

  if (bytes.length === 0) {
    failures.push(failure("log-empty"));
  }
  if (nnapiExecutionProviderMentionCount === 0) {
    failures.push(failure("onnxruntime-nnapi-provider-not-observed"));
  }
  if (referenceTokenCount > 0) {
    failures.push(
      failure("nnapi-reference-cpu-route-observed", {
        tokenCount: referenceTokenCount,
        compilationCount: referenceDevices.reduce(
          (total, item) => total + item.compilationCount,
          0,
        ),
      }),
    );
  }
  if (devices.length === 0) {
    failures.push(failure("nnapi-compilation-device-not-proven"));
  }
  if (knownAcceleratorDevices.length === 0) {
    failures.push(failure("known-hardware-accelerator-not-observed"));
  }
  if (unclassifiedDevices.length > 0) {
    failures.push(
      failure(
        "nnapi-compilation-device-unclassified",
        unclassifiedDevices.map((item) => item.name),
      ),
    );
  }
  if (environmentInterrupted) {
    failures.push(
      failure("environment-timeout", {
        freezeEventCount,
        showWhenLockedDeniedCount,
      }),
    );
  } else if (showWhenLockedDeniedCount > 0) {
    warnings.push(
      failure("show-when-locked-denied-without-observed-freeze", {
        count: showWhenLockedDeniedCount,
      }),
    );
  }

  const hardwareAcceleratorClaimAllowed =
    failures.length === 0 &&
    referenceTokenCount === 0 &&
    knownAcceleratorDevices.length > 0;

  return {
    schemaVersion: SCHEMA_VERSION,
    generatedAt,
    status: hardwareAcceleratorClaimAllowed ? "PASS" : "FAIL",
    runCompletion: environmentInterrupted
      ? "environment-timeout"
      : "not-established-by-device-log",
    scope: "android-nnapi-compilation-device-route",
    source: {
      file: basename(String(logFileName)),
      bytes: bytes.length,
      sha256: sha256(bytes),
    },
    observations: {
      nnapiExecutionProviderMentionCount,
      compilationDeviceLineCount: devices.reduce(
        (total, item) => total + item.compilationCount,
        0,
      ),
      devices,
      nnapiReferenceTokenCount: referenceTokenCount,
      environmentInterrupted,
      freezeEventCount,
      showWhenLockedDeniedCount,
    },
    claims: {
      hardwareAcceleratorClaimAllowed,
      nnapiProviderLabelAloneSufficient: false,
    },
    failures,
    warnings,
  };
}

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith("--") || index + 1 >= argv.length) {
      throw new Error("Expected --log and optional --output arguments");
    }
    values[key.slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!values.log) {
    throw new Error("--log is required");
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
      // A successful rename removes the temporary path.
    }
  }
}

function main(argv) {
  const args = parseArgs(argv);
  const logPath = resolve(args.log);
  const result = analyzeNnapiDeviceLog({
    logBytes: readFileSync(logPath),
    logFileName: basename(logPath),
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
      `NNAPI device-log validation could not start: ${error instanceof Error ? error.message : "unknown error"}\n`,
    );
    process.exitCode = 2;
  }
}
