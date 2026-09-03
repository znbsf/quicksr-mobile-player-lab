# Mobile anime VFI candidate probe

Date: 2026-09-03

Status: candidate refresh, one host rerun, and one bounded physical-device resident native probe
completed. The result remains offline-only. No file under `app/` changed, no model was added to the
player, and no realtime, sustained-thermal, representative-anime, or redistribution claim is made.

## Decision

Do not replace RIFE ncnn Vulkan v4.6 with IFRNet-S. IFRNet-S uses about half the sampled PSS, but it
is 34-113% slower across the same three resident fixture levels. Its synthetic midpoint proxies are
mixed, and it is dramatically worse on the original distinct-pair fixture. The candidate is stopped;
it must not be integrated merely because it is smaller.

The next priority is a candidate-specific current ncnn runtime for `rife-v4.25-lite`, followed by the
same host gate. Its model cannot run in the already-measured 2022 RIFE executable: the actual smoke
test reports `layer MemoryData not exists or registered` and terminates before inference. A later
device probe would be a separate task. If that route fails, progress requires either a licensed
anime-lightweight weight/training path or an SM8550/HTP V73-specific ANVIL runtime and context.

RIFE v4.6 remains an offline baseline, not a realtime success.

## First-party source refresh

The shortlist is deliberately limited to two.

| Candidate | Why it survived source screening | Code / weight boundary | Runtime gate | Disposition |
| --- | --- | --- | --- | --- |
| RIFE v4.25-lite ncnn | [Practical-RIFE at `bbfd2ea`](https://github.com/hzwer/Practical-RIFE/tree/bbfd2ea90910789a860ea3e2b32a240cd577b75e) says v4.25 improves anime scenes and defines `lite` as lower compute; [TNTwise ncnn port at `13338e3`](https://github.com/TNTwise/rife-ncnn-vulkan/tree/13338e38debe2e400b3eeecf6792312d01a692f9) contains the converted model | Both repositories are MIT. Practical-RIFE says linked trained models share its MIT license, but transformed-weight redistribution is still blocked here pending a human provenance review | 11,312,473 checked-out bytes; custom `rife.Warp` plus `MemoryData`, conv/deconv, interpolation, pixel shuffle, elementwise and split/concat operators; no published Android binary | Next runtime-specific host probe; not device-tested |
| IFRNet-S Vimeo90K ncnn | [IFRNet paper](https://openaccess.thecvf.com/content/CVPR2022/papers/Kong_IFRNet_Intermediate_Feature_Refine_Network_for_Efficient_Frame_Interpolation_CVPR_2022_paper.pdf), [author code at `b117bca`](https://github.com/ltkong218/IFRNet/tree/b117bcafcf074b2de756b882f8a6ca02c3169bfe), and [ncnn port at `3592a70`](https://github.com/nihui/ifrnet-ncnn-vulkan/tree/3592a70355ec011fe7cefb3a9ba08b63d82a2b6d) provide executable code and a 5,935,644-byte model | Code is MIT; no independent weight-license statement was found, so model redistribution remains blocked | ncnn Vulkan; custom warp, conv/deconv, interpolation, PReLU, sigmoid, slice/split/concat; project-cross-compiled native Android CLI | Real host/device lower-bound probe completed; stopped |

The upstream RIFE model note is anime-oriented evidence, not project validation on representative
anime. IFRNet-S is trained for Vimeo90K natural video; its paper mentions cartoon creation as an
application, not an anime training domain.

Two names were checked but not added to the shortlist:

- Exact-name searches for `MobileVFI` did not identify a first-party paper, author repository, and
  released weight under that name. It is treated as unresolved, not silently mapped to another work.
- [ANVIL](https://arxiv.org/abs/2603.26835) is a credible mobile VFI system with
  [MIT training/export code](https://github.com/NihilDigit/anvil/tree/f701fd9b01b9fbb20822637b5d82cac626079e5f),
  [MIT model files](https://huggingface.co/NihilDigit/anvil), and an
  [Android demo](https://github.com/NihilDigit/mpv-android-anvil/tree/1efc82739f6f7034cd63e7ee8b3ec5431df27467).
  It is not an image-pair model: it requires H.264 decoder motion-vector side data plus CPU, Vulkan,
  and QNN stages. The published v1.3.0 APK is explicitly SM8650/HTP V75 only. The source documents
  SM8550/V73 measurements, but switching the demo requires V73 Stub/Skel names and a compatible QNN
  context/build. It therefore cannot be compared honestly by feeding the existing PNG pair fixture
  to the public APK.

The exact source pins, model hashes, operators, license states, and screening reasons are in
`vfi-benchmark/candidates.json`.

## Preserved fail-closed contract

The existing `anime-vfi-prefilter-v1` contract was reused without relaxation:

1. no previous frame, stream-epoch change, or seek/generation change: `BYPASS`;
2. exact and near holds: `BYPASS`;
3. hard cuts: `BYPASS`;
4. only the distinct same-stream pair: `INTERPOLATE / DISTINCT_DRAWING`.

The host fixture again produced exactly one interpolated event out of 12. The resident fixture uses
seven project-generated source frames and six exact known midpoints per resolution; all six adjacent
pairs replay as distinct. No external media is involved.

## Host gate

The host measurement is whole subprocess wall time, including fresh runtime/model startup and PNG
I/O. It is not inner-kernel time.

| Candidate | Three process times | Median | Peak private bytes | PSNR | Global SSIM | Edge MAE |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| RIFE v4.6 baseline | 758.99 / 749.83 / 735.96 ms | 749.83 ms | 301,469,696 | 27.120 | 0.983676 | 0.009356 |
| IFRNet-S rerun | 661.65 / 553.59 / 549.63 ms | 553.59 ms | 127,315,968 | 16.604 | 0.798559 | 0.015838 |

IFRNet-S is 26.2% faster and uses 57.8% less peak private memory in this fresh-process host test,
but its one distinct-pair output loses 10.516 dB PSNR and 0.185117 global SSIM while edge error rises
by 0.006482. The output was deterministic across all three runs. Human review remains pending.

## Physical-device resident matrix

The ncnn port was pinned at `3592a703`, ncnn at `30ab31cc`, and libwebp at `5a2d929c`, then
cross-compiled with Android NDK 25.2.9519653 as an arm64 standalone CLI. No APK was produced.
One unpolled process provides latency; a separate process provides PSS/RSS sampling. For each level,
the first midpoint is warmup and the next five are the stable distribution. All 14 output hashes
matched between the latency and sampled processes before the latency outputs were pulled and scored.

| Input -> padded | IFRNet-S stable calls | Median / min / max | RIFE median | IFRNet PSS / RSS | RIFE PSS | IFRNet PSNR / SSIM / edge |
| --- | --- | --- | ---: | ---: | ---: | --- |
| `160x90 -> 160x96` | 34.107 / 40.718 / 42.209 / 42.682 / 41.798 ms | 41.798 / 34.107 / 42.682 ms | 30.260 ms | 94,427 / 135,780 kB | 186,336 kB | 28.778 / 0.985774 / 0.010051 |
| `256x144 -> 256x160` | 64.253 / 61.184 / 59.686 / 60.912 / 60.076 ms | 60.912 / 59.686 / 64.253 ms | 45.441 ms | 93,526 / 134,860 kB | 187,337 kB | 28.682 / 0.987330 / 0.006561 |
| `320x180 -> 320x192` | 80.267 / 75.932 / 74.101 / 73.806 / 78.419 ms | 75.932 / 73.806 / 80.267 ms | 35.715 ms | 97,329 / 138,644 kB | 185,929 kB | 27.300 / 0.983820 / 0.010337 |

Relative to the frozen RIFE matrix, IFRNet-S is 38.1%, 34.0%, and 112.6% slower while sampled PSS
falls 47.7-50.1%. Its PSNR is 0.209-0.922 dB higher on these six-midpoint synthetic fixtures and two
levels also improve the other proxies, but 160x90 SSIM and 320x180 edge error regress. These mixed
synthetic results neither erase the original distinct-pair failure nor establish anime quality.

Cold Vulkan initialization was 23.801-25.057 ms, model loading was 930.589-987.369 ms, warmup was
171.890-216.244 ms, and whole-process time was 1563.969-1850.021 ms. Maximum thermal-zone proxies
were 44.383-44.9 C before and 46.5-47.6 C after; the battery proxy stayed at 36.5 C. This short run is
not sustained thermal evidence. The process was absent after the matrix.

The 160x90 median misses the 24 fps kernel-only budget by 0.131 ms and its maximum misses it by
1.015 ms. The 256x144 level misses both 24 and 30 fps. The 320x180 median fits only inside one
83.333 ms 12-to-24 source interval, leaving 7.401 ms before decode, SR, composition, scheduling,
A/V sync, and display. There is no defensible end-to-end realtime margin.

## Reproduction and evidence boundary

The committed build script applies an instrumentation patch only to the exact upstream commit and
verifies both submodule commits. The generic resident runner validates fixture files and decisions,
checks every pushed input, requires complete timing IDs, compares outputs across both processes,
and then scores the known midpoints.

Commands and arguments are in `vfi-benchmark/README.md`. Sanitized measurements are in
`vfi-benchmark/mobile-candidate-evidence-summary.json`. The raw reports, images, model files, source
clones, binaries, and device logs remain ignored below `local-artifacts/`; the device serial is not
in committed material. Publication scanning does not confer model redistribution rights.
