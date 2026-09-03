from __future__ import annotations

import unittest

from run_vfi_android_resident_matrix import (
    EXPECTED_MIDPOINT_IDS,
    parse_timing,
    validate_safe_remote_component,
)


class ResidentMatrixTimingTest(unittest.TestCase):
    def make_timing(self) -> str:
        lines = [
            "VFI_VULKAN_INIT_WALL_NS=23000000",
            "VFI_MODEL_LOAD_WALL_NS gpu=0 ns=900000000",
        ]
        for task_id in range(14):
            timestep = 0.5 if task_id in EXPECTED_MIDPOINT_IDS else 0.0
            lines.extend(
                [
                    f"VFI_DECODE_WALL_NS id={task_id} ns={1000 + task_id}",
                    (
                        f"VFI_MODEL_WALL_NS id={task_id} ns={2000 + task_id} "
                        f"timestep={timestep:.6f} width=160 height=90 "
                        "padded_width=160 padded_height=96"
                    ),
                    f"VFI_ENCODE_WALL_NS id={task_id} ns={3000 + task_id}",
                ]
            )
        return "\n".join(lines)

    def test_complete_timing_stream_is_bound_to_expected_ids(self) -> None:
        result = parse_timing(self.make_timing())
        self.assertEqual([item["id"] for item in result["midpoint_calls"]], EXPECTED_MIDPOINT_IDS)
        self.assertEqual(result["model_load"]["wall_time_ns"], 900000000)

    def test_incomplete_timing_stream_fails_closed(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "expected 14"):
            parse_timing(self.make_timing().replace("VFI_ENCODE_WALL_NS id=13 ns=3013", ""))

    def test_remote_components_reject_shell_syntax_and_paths(self) -> None:
        validate_safe_remote_component("model", "IFRNet_S_Vimeo90K")
        parent_component = "." * 2
        for value in (parent_component, "model\\name", "model/name", "model;echo", "model name"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                validate_safe_remote_component("model", value)


if __name__ == "__main__":
    unittest.main()
