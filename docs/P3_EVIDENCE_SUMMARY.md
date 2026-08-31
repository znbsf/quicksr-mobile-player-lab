# P3 QNN HTP evidence summary

This is a source-only historical summary. Raw receipts, tensors, profiles, traces, APKs, vendor libraries, host paths, and device identifiers are deliberately not copied into this repository.

## Question answered

P3 tested whether the fixed-shape QuickSRNetSmall 2× DCR graph could execute through the ONNX Runtime QNN plugin on the Xiaomi 13 Ultra HTP without CPU execution-provider fallback. It did not test a complete image pipeline or player.

## Preserved aggregate result

| Evidence axis | Historical result | Boundary |
| --- | --- | --- |
| Runtime | PASS | 5/5 warmups and 30/30 measured runs completed |
| ORT placement | PASS | 35/35 profiled model events were assigned to QNN EP |
| QNN graph | PASS | HTP V73, 11/11 supported nodes, zero unsupported nodes |
| Accelerator trace | PASS | Each measured run had QNN execute, RPC execute, accelerator-cycle, and HVX-thread evidence |
| CPU fallback policy | PASS | CPU EP fallback was disabled for the strict session |
| Build linkage | PASS | The original P3 source/build/APK/receipt identities were linked |
| Frozen PC golden | **FAIL** | 28,764/49,152 mismatches; max abs 0.0015161633491516113; mean abs 0.0002611008830958698 |
| Aggregate machine gate | **FAIL** | Execution evidence cannot override correctness failure |
| Human review | PENDING | No machine result can advance it |

The P3 elementwise contract was frozen before the run as `abs(android - pc) <= 1e-4 + 1e-4 * abs(pc)`, with zero mismatches and zero non-finite values allowed. The observed output exceeded that contract, so the failure is retained.

Two strict HTP runs had the same output hash, which supports repeatability for that configuration. Repeatability is not correctness.

## Timing boundary

The historical profiled `Session.run` P50/P95 was approximately 1.535/1.683 ms for one `64×64 → 128×128` tile with detailed profiling enabled. This excludes image decode, colorspace conversion, full-frame tiling, copying, rendering, video synchronization, thermal behavior, and player backpressure. It must not be converted into a full-frame or video FPS claim.

## Precision interpretation

The error class is consistent with Qualcomm documentation that newer QAIRT HTP floating-point execution uses FP16 math on supported devices even when older precision controls suggest FP32. That explains why an FP32 host reference can differ; it does not retroactively change the failed P3 threshold and does not define a universal model tolerance.

Primary references:

- [ONNX Runtime QNN execution provider documentation](https://github.com/onnxruntime/onnxruntime-qnn/blob/v2.5.0/docs/execution_providers/QNN-ExecutionProvider.md)
- [Qualcomm QAIRT 2.33 partner release notes](https://docs.qualcomm.com/doc/KBA-250421151446/KBA-250421151446_REV_1_QAIRT_2_33_0_Partner_Release_Notes.pdf)
- [ORT QNN HTP framework trace test](https://github.com/onnxruntime/onnxruntime-qnn/blob/v2.5.0/onnxruntime/test/providers/qnn/framework_op_trace_test.cc)

## Upstream feedback already separated

The evidence produced two narrowly scoped upstream documentation contributions:

- [onnxruntime-qnn PR 782](https://github.com/onnxruntime/onnxruntime-qnn/pull/782): HTP precision-option documentation semantics.
- [onnxruntime-qnn PR 783](https://github.com/onnxruntime/onnxruntime-qnn/pull/783): Android Maven/runtime version semantics.

This repository intentionally avoids freezing a volatile PR status. Upstream state must be rechecked in the dedicated upstream task before any claim about open, approved, merged, or CI status.

## What P4 must add

P4 has a new pre-run contract in `contracts/p4-real-image-roi-plan.json`. It requires held-out synthetic inputs, a rights-safe real image ROI, PC-versus-HTP numerical comparison, rendered image metrics, independent human review, and failure/retest linkage. Its device gate remains blocked until SSIM and all acceptance input hashes are frozen.
