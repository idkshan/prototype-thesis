import sys
from pathlib import Path

import cv2
import numpy as np
import yaml

from dms.camera.esp32_look import ESP32_SIZE, Esp32LikeSource, crop_to_aspect, esp32_augment, esp32_frame
from dms.camera.sources import Frame

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))


def _picture(w=640, h=480, seed=0):
    rng = np.random.default_rng(seed)
    img = np.full((h, w, 3), 120, np.uint8)
    cv2.circle(img, (w // 2, h // 2), min(w, h) // 4, (200, 180, 160), -1)
    return np.clip(img + rng.normal(0, 10, img.shape), 0, 255).astype(np.uint8)


def test_live_frame_is_480x320_and_compressed():
    out = esp32_frame(_picture())
    assert out.shape == (ESP32_SIZE[1], ESP32_SIZE[0], 3)
    assert crop_to_aspect(_picture(640, 480)).shape[:2] == (427, 640)  # 4:3 webcam -> 3:2


def test_augment_keeps_shape_ratio_and_fits_the_camera():
    rng = np.random.default_rng(1)
    for w, h in ((1280, 720), (640, 480), (300, 200)):
        out = esp32_augment(_picture(w, h), rng)
        oh, ow = out.shape[:2]
        assert abs(ow / oh - w / h) < 0.02  # never cropped: YOLO labels stay valid
        assert ow <= max(w, ESP32_SIZE[0]) and oh <= max(h, ESP32_SIZE[1])
        if w > ESP32_SIZE[0]:
            assert ow <= ESP32_SIZE[0] and oh <= ESP32_SIZE[1]


def test_source_wrapper_caps_the_frame_rate():
    frames = [Frame(image=_picture(), t=i / 30.0, index=i) for i in range(60)]  # 2 s of a 30 fps webcam
    out = list(Esp32LikeSource(frames, fps=15))
    assert 29 <= len(out) <= 31
    assert [f.index for f in out] == list(range(len(out)))
    assert out[0].image.shape == (320, 480, 3)


def test_dataset_copy(tmp_path):
    import make_esp32_look

    src = tmp_path / "src"
    for split in ("train", "valid", "test"):
        (src / split / "images").mkdir(parents=True)
        (src / split / "labels").mkdir(parents=True)
        for i in range(2):
            cv2.imwrite(str(src / split / "images" / f"{split}{i}.jpg"), _picture(seed=i))
            (src / split / "labels" / f"{split}{i}.txt").write_text("1 0.5 0.5 0.2 0.2\n")
    (src / "data.yaml").write_text("train: ../train/images\nval: ../valid/images\ntest: ../test/images\nnames: [eye_closed, eye_open]\n")
    out = tmp_path / "out"
    assert make_esp32_look.main(["--data", str(src / "data.yaml"), "--out", str(out), "--copies", "2"]) == 0
    assert len(list((out / "images" / "train").glob("*.jpg"))) == 6  # 2 originals + 2 x 2 copies
    assert len(list((out / "images" / "val").glob("*.jpg"))) == 4
    assert len(list((out / "images" / "test").glob("*.jpg"))) == 2
    assert len(list((out / "images" / "test_esp32").glob("*.jpg"))) == 2
    assert (out / "labels" / "train" / "train0__esp32_1.txt").read_text() == "1 0.5 0.5 0.2 0.2\n"
    d = yaml.safe_load((out / "data_esp32_test.yaml").read_text())
    assert d["test"] == "images/test_esp32" and d["names"] == ["eye_closed", "eye_open"]
