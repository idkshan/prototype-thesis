"""Camera aiming (scripts/aim_camera.py): angle, OK / not OK, and which way to move the camera."""

import cv2
import numpy as np
import pytest

from dms.camera.aiming import CameraAimer, advice
from dms.features.head_pose import MODEL_POINTS, POSE_LANDMARKS, camera_matrix, estimate_head_pose

W, H = 640, 480


def _face_pointing(rvec):
    """Landmarks of the generic face model, rotated by rvec, 3 m in front of the camera."""
    pts, _ = cv2.projectPoints(MODEL_POINTS, np.array(rvec, float), np.array([0.0, 0.0, 3000.0]),
                               camera_matrix(W, H), np.zeros(4))
    lm = np.zeros((478, 3))
    lm[list(POSE_LANDMARKS), :2] = pts.reshape(-1, 2)
    return lm


def test_pose_signs_match_the_camera_position():
    # The driver looks at the road; the camera sits to the driver's RIGHT, so in the
    # picture the nose points toward the image's right (+x): yaw > 0 -> move the camera LEFT.
    th = np.radians(30)
    yaw, pitch, _ = estimate_head_pose(_face_pointing([0.0, -th, 0.0]), W, H)
    assert yaw == pytest.approx(30, abs=2) and abs(pitch) < 2
    assert advice(yaw, pitch, 25.0).horizontal.startswith("move LEFT")
    # camera BELOW the face (dashboard): the nose points up in the picture (-y): pitch > 0 -> move UP
    yaw, pitch, _ = estimate_head_pose(_face_pointing([-th, 0.0, 0.0]), W, H)
    assert pitch == pytest.approx(30, abs=2) and abs(yaw) < 2
    assert advice(yaw, pitch, 25.0).vertical.startswith("move UP")


def test_advice_limits_and_directions():
    a = advice(30.0, 12.0, 25.0)
    assert a.offset == pytest.approx(np.hypot(30, 12)) and not a.ok
    assert a.horizontal == "move LEFT 30 deg" and a.vertical == "move UP 12 deg"
    a = advice(-14.0, -5.0, 25.0)
    assert a.ok and a.horizontal == "move RIGHT 14 deg" and a.vertical == "good"
    assert advice(None, 0.0, 25.0).offset is None and "no face" in advice(None, None, 25.0).summary()


def test_aimer_smooths_and_resets_without_a_face():
    aimer = CameraAimer(25.0, window=5)
    for y in (40, 40, 40, 5, 40):  # one wrong frame does not move the median
        a = aimer.update(float(y), 0.0)
    assert a.yaw == pytest.approx(40) and not a.ok
    assert aimer.update(None, None).offset is None
    assert aimer.update(10.0, 0.0).yaw == pytest.approx(10)  # fresh start after the face came back


def test_aim_window_draws():
    import aim_camera

    frame = np.zeros((H, W, 3), np.uint8)
    out = aim_camera.draw_aim(frame, advice(30.0, 5.0, 25.0), nose=(320, 240))
    assert out.shape[0] > H and out.shape[2] == 3
    assert aim_camera.draw_aim(frame, advice(None, None, 25.0)).shape[0] > H
