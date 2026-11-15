"""The Android app's guided recording writes labels.csv itself (dms/Guided.kt): check that the
evaluation reads its files without warnings (the same files are checked by GuidedTest.kt)."""

from pathlib import Path

import pytest

from dms.evaluation.labels import load_labels

GOLDEN = Path(__file__).resolve().parents[1] / "androidapp" / "app" / "src" / "test" / "resources" / "golden"


@pytest.mark.parametrize("script", ["quick", "standard"])
def test_guided_label_files_are_valid(script):
    labels = load_labels(GOLDEN / f"guided_{script}_labels.csv", strict=True)
    for behavior in ("drowsiness", "yawning", "gaze_deviation", "phone_usage", "eye_closure"):
        assert labels.segments[behavior], behavior
    assert labels.drowsiness_levels[0][2] == "unspecified"
