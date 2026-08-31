# Publication boundary

## Policy

This project is **source-only and private-first**.

`Private-first` means development starts in a private GitHub repository while
the source tree, evidence, and licenses are cleaned. It does not authorize
committing files that are unlicensed, sensitive, generated, or too large for
the source repository. The same source-only gate applies to private and public
remotes.

The current repository demonstrates runtime integration and reproducible
validation. It does not grant permission to redistribute a trained model,
Qualcomm runtime binaries, or an installable application containing them.

## Allowed in Git

- Original Java, Python, PowerShell, and JavaScript source.
- Gradle configuration and text-only build metadata.
- Unit tests and validators.
- Frozen experiment contracts, source URLs, version pins, byte counts, and
  SHA-256 values.
- Architecture, design rationale, problem logs, and claim boundaries.
- Synthetic fixture metadata explicitly labelled as fixture/example.
- Manually reviewed aggregate evidence that contains no raw tensor, device
  receipt, trace, local path, unique device identifier, or credential.
- A standard Gradle wrapper only when it has official provenance and
  `gradle-wrapper.properties` pins an HTTPS distribution plus a valid
  `distributionSha256Sum`.

## Never commit

### Models and training artifacts

- Checkpoints and weights, including PTH, PT, CKPT, SafeTensors, TFLite, DLC,
  GGUF, engine, and ONNX files.
- Fixed-shape or otherwise transformed ONNX models: transformations do not
  remove the embedded trained weights or solve their license boundary.
- Training, calibration, evaluation, or real-media datasets.

The local `models/` and `derived-models/` payloads may be used for a personal
experiment but must remain ignored. Git stores preparation tools, hashes, and
instructions instead.

### Vendor and compiled binaries

- APK, AAB, AAR, SO, DLL, DYLIB, EXE, DEX, CLASS, JAR, OBJ, LIB, and similar
  compiled payloads.
- Extracted ONNX Runtime or QNN libraries.
- Qualcomm HTP/DSP backend, system, prepare, stub, or skel libraries.
- Build output, Gradle caches, local SDK state, and signing material.

The only prospective binary exception is the official Gradle wrapper JAR,
subject to the checksum and provenance rule above.

### Raw evidence and device state

- Device receipts and app-private files.
- ORT profiles, QNN profiling CSV, framework-op traces, input-graph dumps,
  Android logs, host logs, and crash dumps.
- Raw input/output tensors, Base64-preserved raw bytes, benchmark scratch,
  screenshots, video, audio, and other unreviewed media.
- Device serials, Android fingerprints, account names, package-private data
  paths, machine-specific absolute paths, battery state, or other unnecessary
  personal telemetry.

Raw evidence belongs in an ignored local artifact directory. A future public
evidence record must be generated as a small aggregate with a documented
redaction review and independent hash references.

### Secrets

- Environment files, API keys, access tokens, passwords, cookies, signing
  keys, private keys, keystores, certificates with private material, and
  service credentials.
- Qualcomm AI Hub credentials or Android signing configuration.

## APK and model distribution are separate decisions

A source repository that declares Maven dependencies is not the same as an APK
distribution. Before publishing an APK, all of the following need an explicit
review:

1. Model/checkpoint and training-data rights.
2. Qualcomm QNN Runtime license and required notices.
3. ONNX Runtime and plugin notices.
4. Rights for every sample image, clip, icon, and font.
5. Signing, update, privacy, and telemetry behavior.

Until those gates close, GitHub Releases must not contain a model, APK, AAB,
or binary dependency bundle.

## Mandatory publication check

From the independent repository root, after initializing Git, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-publication.ps1
```

The default mode inspects tracked files plus non-ignored untracked files: the
set that could be added or published. It fails if the current directory is not
the independent Git root; this prevents accidentally treating a parent
repository as the publication boundary.

For a deliberately stricter local audit, including ignored local artifacts,
run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-publication.ps1 -ScanAllFiles
```

That mode is expected to fail while local models or raw experiment artifacts
are present. It is diagnostic and does not mean those ignored files must be
deleted.

An exit code of zero means only that the automated deny rules found no match.
It does not grant a license, prove that a model or media asset is lawful, or
replace human review.

## Release checklist

1. Confirm the Git top-level directory is this independent project.
2. Review `git status` and the exact staged file list.
3. Run `scripts/verify-publication.ps1` and require exit code zero.
4. Inspect the diff for unsupported claims and broken links.
5. Confirm the project license and `THIRD_PARTY_NOTICES.md` are present.
6. Confirm models, APKs, vendor binaries, raw evidence, and credentials are
   absent from both the current tree and Git history.
7. Obtain explicit human approval before changing a remote from private to
   public or attaching a binary release.
