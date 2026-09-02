"""Deterministic, stateful prefilter for the opt-in offline VFI experiment.

The contract deliberately operates on decoded frames rather than frame indices.  A stream or
generation change invalidates the previous frame, exact and near holds are bypassed, and hard cuts
are bypassed.  Only a genuine distinct drawing within one stream/generation is eligible for VFI.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image


PREFILTER_ID = "anime-vfi-prefilter-v1"


@dataclass(frozen=True)
class PrefilterConfig:
    sample_width: int = 96
    sample_height: int = 54
    near_hold_mad_max: float = 0.012
    near_hold_changed_fraction_max: float = 0.08
    changed_pixel_delta: float = 0.035
    hard_cut_mad_min: float = 0.24
    hard_cut_changed_fraction_min: float = 0.72

    @property
    def identity(self) -> str:
        payload = repr(sorted(asdict(self).items())).encode("utf-8")
        return sha256(PREFILTER_ID.encode("utf-8") + b"\0" + payload).hexdigest()


@dataclass(frozen=True)
class FrameRecord:
    frame_id: str
    path: str
    sha256: str
    stream_id: str
    generation: int


@dataclass(frozen=True)
class Decision:
    decision: str
    reason: str
    pair_identity: str | None
    previous_frame_id: str | None
    current_frame_id: str
    stream_id: str
    generation: int
    prefilter_id: str
    config_sha256: str
    metrics: dict[str, float]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def file_sha256(path: str | Path) -> str:
    digest = sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_luma(path: str | Path, config: PrefilterConfig) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(
            image.convert("L").resize(
                (config.sample_width, config.sample_height), Image.Resampling.BOX
            ),
            dtype=np.float32,
        ) / 255.0


def pair_metrics(previous_luma: np.ndarray, current_luma: np.ndarray, config: PrefilterConfig) -> dict[str, float]:
    delta = np.abs(current_luma - previous_luma)
    return {
        "luma_mad": float(np.mean(delta)),
        "luma_p95": float(np.percentile(delta, 95)),
        "changed_fraction": float(np.mean(delta >= config.changed_pixel_delta)),
    }


class VfiPrefilter:
    """Consumes frames in presentation order and owns the previous-frame reset contract."""

    def __init__(self, config: PrefilterConfig | None = None) -> None:
        self.config = config or PrefilterConfig()
        self._previous: FrameRecord | None = None
        self._previous_luma: np.ndarray | None = None

    def _replace_anchor(self, frame: FrameRecord, luma: np.ndarray) -> None:
        self._previous = frame
        self._previous_luma = luma

    def observe(self, frame: FrameRecord) -> Decision:
        current_luma = load_luma(frame.path, self.config)
        previous = self._previous
        previous_luma = self._previous_luma

        if previous is None or previous_luma is None:
            self._replace_anchor(frame, current_luma)
            return self._decision("BYPASS", "NO_PREVIOUS_FRAME", None, frame, {})

        if previous.stream_id != frame.stream_id:
            self._replace_anchor(frame, current_luma)
            return self._decision("BYPASS", "STREAM_EPOCH_RESET", previous, frame, {})

        if previous.generation != frame.generation:
            self._replace_anchor(frame, current_luma)
            return self._decision("BYPASS", "GENERATION_RESET", previous, frame, {})

        metrics = pair_metrics(previous_luma, current_luma, self.config)
        pair_identity = sha256(
            f"{previous.sha256}:{frame.sha256}:{frame.stream_id}:{frame.generation}".encode("utf-8")
        ).hexdigest()

        if previous.sha256 == frame.sha256:
            result = self._decision("BYPASS", "HOLD_EXACT", previous, frame, metrics, pair_identity)
        elif (
            metrics["luma_mad"] <= self.config.near_hold_mad_max
            and metrics["changed_fraction"] <= self.config.near_hold_changed_fraction_max
        ):
            result = self._decision("BYPASS", "HOLD_NEAR", previous, frame, metrics, pair_identity)
        elif (
            metrics["luma_mad"] >= self.config.hard_cut_mad_min
            and metrics["changed_fraction"] >= self.config.hard_cut_changed_fraction_min
        ):
            result = self._decision("BYPASS", "HARD_CUT", previous, frame, metrics, pair_identity)
        else:
            result = self._decision(
                "INTERPOLATE", "DISTINCT_DRAWING", previous, frame, metrics, pair_identity
            )

        self._replace_anchor(frame, current_luma)
        return result

    def _decision(
        self,
        decision: str,
        reason: str,
        previous: FrameRecord | None,
        current: FrameRecord,
        metrics: dict[str, float],
        pair_identity: str | None = None,
    ) -> Decision:
        return Decision(
            decision=decision,
            reason=reason,
            pair_identity=pair_identity,
            previous_frame_id=previous.frame_id if previous else None,
            current_frame_id=current.frame_id,
            stream_id=current.stream_id,
            generation=current.generation,
            prefilter_id=PREFILTER_ID,
            config_sha256=self.config.identity,
            metrics=metrics,
        )
