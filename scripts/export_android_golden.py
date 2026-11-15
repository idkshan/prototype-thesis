"""Write the reference data the Android app's tests compare against (desktop = reference).

    python scripts/export_android_golden.py

The phone app (androidapp/) runs a Kotlin port of Stages 2-5 and the alert state
machine. Its unit tests replay the inputs written here and must reproduce the
desktop decisions frame by frame:

  engine.jsonl.gz   test drives (every scenario of simulate_scenarios.py, synthetic
                    drives with nuisance behaviour, YOLO eye/yawn evidence, camera
                    stalls with "monitoring unavailable", a tuned config): per frame
                    the FrameFeatures and context fed to the engine and the expected
                    states, behaviour, severity, risk and alert commands
  features.json     Stage 2: real MediaPipe landmarks (face photos in --photos, also
                    rotated in 3-D) and YOLO boxes -> expected FrameFeatures. Only the
                    landmark numbers are saved, never the photos.
  context.json      sensor-board JSON readings -> expected speed / motion / duration

Run it again after changing the Python rules, then run the app's unit tests
(Android Studio: right-click app/src/test -> Run tests, or ./gradlew test).
"""

import argparse
import gzip
import json
import sys
from dataclasses import replace
from pathlib import Path

import numpy as np

import _bootstrap  # noqa: F401
from dms.config import Config, _merge, load_config
from dms.context import HttpContext, StaticContext
from dms.engine import DecisionEngine
from dms.features.extractor import FeatureExtractor
from dms.simulation import ScenarioBuilder, synthetic_drive
from dms.types import Detection, FaceObservation, PerceptionResult
from export_android_config import android_config

OUT = Path(__file__).resolve().parents[1] / "androidapp" / "app" / "src" / "test" / "resources" / "golden"
CAL = 8.0


def _r(x):
    return None if x is None else round(float(x), 7)


def rounded(frames):
    """Round the inputs BEFORE running the engine, so the file stays small and the reference stays exact."""
    out = []
    for f in frames:
        d = {k: (round(v, 5) if isinstance(v, float) else v) for k, v in f.to_dict().items()}
        out.append(replace(f, **d))
    return out


def expected(out, alerts) -> dict:
    s, b, sev, r = out.states, out.behavior, out.severity, out.risk
    return {
        "states": {"eyes_closed": s.eyes_closed, "eye_evidence": _r(s.eye_evidence), "mouth_open": s.mouth_open,
                   "mouth_evidence": _r(s.mouth_evidence), "head_away": s.head_away, "pose_evidence": _r(s.pose_evidence),
                   "phone_visible": s.phone_visible, "face_status": s.face_status, "eyes_not_judged": s.eyes_not_judged,
                   "frame_gap": s.frame_gap},
        "behavior": {"closure_s": _r(b.closure_s), "perclos": _r(b.perclos), "mouth_open_s": _r(b.mouth_open_s),
                     "yawns_in_window": b.yawns_in_window, "gaze_away_s": _r(b.gaze_away_s), "offroad_ratio": _r(b.offroad_ratio),
                     "phone_s": _r(b.phone_s), "long_closures": b.long_closures, "blink_rate_per_min": _r(b.blink_rate_per_min),
                     "eye_level": b.eye_level, "drowsiness_state": b.drowsiness_state, "eye_closure": b.eye_closure,
                     "yawning": b.yawning, "gaze_deviation": b.gaze_deviation, "phone_usage": b.phone_usage,
                     "events": b.events, "active": b.active, "no_face": b.no_face, "data_valid": b.data_valid},
        "severity": {k: [v.score, v.level] for k, v in sev.as_dict().items()},
        "risk": {"fatigue": r.fatigue, "distraction": r.distraction, "context": r.context, "risk": r.risk,
                 "tier": r.tier, "raw_tier": r.raw_tier, "fatigue_level": r.fatigue_level},
        "calibration": out.calibration_status,
        "alert_state": out.alert_state,
        "active_tier": out.active_tier,
        "alerts": [[a.event, a.tier, a.action, a.push, a.id, a.alert_id] for a in alerts],
    }


def run_session(name, frames, cfg_overrides=None, speed_kmh=40.0, motion_at=None) -> dict:
    cfg = load_config(overrides=cfg_overrides or {})
    engine = DecisionEngine(cfg, alert_id_prefix="g")
    ctx = StaticContext(speed_kmh=speed_kmh)
    rows, last_t = [], None
    for f in rounded(frames):
        lost = []
        if last_t is not None and f.t - last_t > cfg.alerts.camera_timeout_s:  # camera stall -> unavailable
            lost = engine.camera_lost(last_t + cfg.alerts.camera_timeout_s, "no frames")
        last_t = f.t
        reading = ctx.read(f.t)
        if motion_at and motion_at[0] <= f.t < motion_at[1]:
            reading = replace(reading, motion_g=motion_at[2])
        reading = replace(reading, driving_s=round(reading.driving_s, 9))  # exactly what the phone test gets
        out = engine.step(f, reading)
        exp = expected(out, out.alerts)
        exp["lost"] = [[a.event, a.tier, a.action, a.push, a.id, a.alert_id] for a in lost]
        rows.append({"f": f.to_dict(), "ctx": {"speed_kmh": reading.speed_kmh, "motion_g": reading.motion_g,
                                               "driving_s": reading.driving_s, "speed_source": reading.speed_source},
                     "exp": exp})
    b = engine.calibrator.baseline
    return {"name": name, "config": cfg_overrides or {}, "frames": rows,
            "final": {"baseline": [b.ear, b.mar, b.yaw, b.pitch, b.blink, b.jaw, b.ear_left, b.ear_right],
                      "warning": engine.calibrator.warning, "episodes": dict(engine.alerts.episodes)}}


def scenario_sessions():
    sys.path.insert(0, str(Path(__file__).parent))
    from simulate_scenarios import SCENARIOS
    for key, (_, build, kw) in SCENARIOS.items():
        b = ScenarioBuilder().alert(CAL)
        build(b)
        yield run_session(f"scenario:{key}", b.build(), speed_kmh=kw.get("speed_kmh", 40.0), motion_at=kw.get("motion_at"))


def with_yolo_evidence(frames, seed):
    """Add YOLO driver-state scores (eye_closed / yawn) that roughly follow the geometry, with noise and misses."""
    rng = np.random.default_rng(seed)
    out = []
    for f in frames:
        if not f.face_present:
            out.append(f)
            continue
        closed = f.blink is not None and f.blink > 0.5
        yawn = f.jaw_open is not None and f.jaw_open > 0.4
        eye = None if rng.random() < 0.2 else float(np.clip((0.8 if closed else 0.2) + rng.normal(0, 0.15), 0, 1))
        mouth = float(np.clip((0.85 if yawn else 0.05) + rng.normal(0, 0.1), 0, 1))
        out.append(replace(f, eye_closed_yolo=eye, yawn_yolo=mouth))
    return out


def with_fusion_evidence(frames, seed):
    """Add fusion-model outputs (P(eyes closed), P(yawning)) that roughly follow the blendshapes."""
    rng = np.random.default_rng(seed)
    out = []
    for f in frames:
        if not f.face_present:
            out.append(f)
            continue
        closed = f.blink is not None and f.blink > 0.5
        yawn = f.jaw_open is not None and f.jaw_open > 0.4
        missing = rng.random() < 0.05  # e.g. head pose not found -> no fusion output
        eye = None if missing else float(np.clip((0.85 if closed else 0.1) + rng.normal(0, 0.12), 0, 1))
        mouth = None if missing else float(np.clip((0.9 if yawn else 0.05) + rng.normal(0, 0.08), 0, 1))
        out.append(replace(f, eye_closed_fusion=eye, yawn_fusion=mouth))
    return out


def with_stall(frames, at_s, stall_s):
    return [replace(f, t=f.t + stall_s) if f.t >= at_s else f for f in frames]


def engine_sessions():
    yield from scenario_sessions()
    frames, _ = synthetic_drive(seed=1, minutes=8)
    yield run_session("synthetic:seed1", frames)
    frames, _ = synthetic_drive(seed=2, minutes=5)
    yield run_session("synthetic:seed2+yolo", with_yolo_evidence(frames[:6000], 2))  # through the slightly-drowsy phase
    frames, _ = synthetic_drive(seed=3, minutes=6)
    yield run_session("synthetic:seed3+stall+motion", with_stall(frames, 150.0, 6.0), motion_at=(200.0, 201.0, 0.55))
    frames, _ = synthetic_drive(seed=4, minutes=6)
    tuned = {"drowsiness": {"recovery_s": 10.0, "slight_long_closures": 3}, "temporal": {"phone_gap_s": 0.5, "gaze_min_s": 2.5},
             "alerts": {"reminder_s": 30.0, "escalate_closure_s": 2.0, "medium_max_repeats": 5},
             "thresholds": {"ear_closed_ratio": 0.72}, "quality": {"look_down_ear_guard": 0.6},
             "severity": {"gaze_s": [[0.0, 0.0], [1.0, 0.0], [2.5, 0.34], [3.5, 0.66], [5.0, 1.0]]}}
    yield run_session("synthetic:seed4+tuned", frames, cfg_overrides=tuned, speed_kmh=None)
    frames, _ = synthetic_drive(seed=5, minutes=4)
    yield run_session("synthetic:seed5+fusion", with_fusion_evidence(frames, 5),
                      cfg_overrides={"evidence": {"eye_fusion": 0.8, "mouth_fusion": 0.3}})
    b = ScenarioBuilder(seed=9).alert(CAL).phone(4).alert(5)
    yield run_session("scenario:phone_stopped_no_gps", b.build(), speed_kmh=0.0)


# ------------------------------------------------------------------ Stage 2
def feature_cases(face_model: Path, photo_dir: Path) -> list:
    import cv2
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision

    lm = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(face_model)), running_mode=vision.RunningMode.IMAGE,
        num_faces=4, output_face_blendshapes=True))
    rng = np.random.default_rng(5)
    extractor = FeatureExtractor(Config().detector)
    cases = []
    photos = sorted(photo_dir.glob("*.jpg"))
    if not photos:
        raise SystemExit(f"no .jpg face photos in {photo_dir}")

    def views(img):
        # the whole photo and zoomed-in tiles: the face model needs faces that fill part of the picture
        yield img
        H, W = img.shape[:2]
        for n in (2, 3):
            th, tw = H // n, W // n
            for y in range(0, H - th + 1, max(1, th // 2)):
                for x in range(0, W - tw + 1, max(1, tw // 2)):
                    yield cv2.resize(img[y:y + th, x:x + tw], None, fx=2.0, fy=2.0)

    for photo in photos:
      for img in views(cv2.imread(str(photo))):
        h, w = img.shape[:2]
        res = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(img, cv2.COLOR_BGR2RGB)))
        for k, lms in enumerate(res.face_landmarks):
            pts = np.array([[p.x * w, p.y * h, p.z * w] for p in lms])
            blend = {c.category_name: float(c.score) for c in res.face_blendshapes[k]}
            variants = [pts]
            centre = pts.mean(axis=0)
            for _ in range(4):  # the same face turned / tilted in 3-D (orthographic re-projection)
                yaw, pitch, roll = np.radians(rng.uniform(-50, 50)), np.radians(rng.uniform(-30, 30)), np.radians(rng.uniform(-20, 20))
                ry = np.array([[np.cos(yaw), 0, np.sin(yaw)], [0, 1, 0], [-np.sin(yaw), 0, np.cos(yaw)]])
                rx = np.array([[1, 0, 0], [0, np.cos(pitch), -np.sin(pitch)], [0, np.sin(pitch), np.cos(pitch)]])
                rz = np.array([[np.cos(roll), -np.sin(roll), 0], [np.sin(roll), np.cos(roll), 0], [0, 0, 1]])
                variants.append((pts - centre) @ (rz @ rx @ ry).T + centre + rng.normal(0, 0.5, size=pts.shape))
            for v in variants:
                v = v.round(3)  # what the file stores is exactly what the expected values come from
                x1, y1, x2, y2 = v[:, 0].min(), v[:, 1].min(), v[:, 0].max(), v[:, 1].max()
                face = FaceObservation(landmarks=v, blendshapes=blend, bbox=(x1, y1, x2, y2))
                fw, fh = x2 - x1, y2 - y1
                dets = [  # phone near the face, phone far away, closed eye inside, open eye outside the face, yawn
                    Detection("cell phone", 0.6, (x2, y1 + fh * 0.3, x2 + fw * 0.4, y2 + fh * 0.4), role="phone"),
                    Detection("cell phone", 0.9, (x2 + fw * 4, y1, x2 + fw * 4.5, y1 + fh * 0.5), role="phone"),
                    Detection("eye_closed", 0.7, (x1 + fw * 0.2, y1 + fh * 0.3, x1 + fw * 0.4, y1 + fh * 0.4), role="eye_closed"),
                    Detection("eye_open", 0.5, (x2 + fw, y1, x2 + fw * 1.2, y1 + fh * 0.1), role="eye_open"),
                    Detection("yawn", float(rng.uniform(0.2, 0.9)), (x1 + fw * 0.3, y1 + fh * 0.7, x1 + fw * 0.7, y1 + fh * 0.95), role="yawn"),
                ]
                roles = {"phone", "eye_open", "eye_closed", "yawn", "no_yawn"} if rng.random() < 0.5 else {"phone"}
                p = PerceptionResult(frame_size=(w, h), face=face, detections=dets, detector_roles=roles)
                f = extractor.extract(p, 1.5)
                cases.append({"w": w, "h": h, "landmarks": v.tolist(), "blendshapes": blend,
                              "detections": [[d.label, d.confidence, list(d.box), d.role] for d in dets],
                              "roles": sorted(roles), "expected": f.to_dict(),
                              "in_roi": [bool(d.in_driver_roi) for d in dets]})
    # no face: only phones count, every phone in view
    p = PerceptionResult(frame_size=(640, 480), face=None, detections=[Detection("cell phone", 0.4, (10, 10, 50, 50), role="phone")],
                         detector_roles={"phone"})
    cases.append({"w": 640, "h": 480, "landmarks": None, "blendshapes": {}, "detections": [["cell phone", 0.4, [10, 10, 50, 50], "phone"]],
                  "roles": ["phone"], "expected": extractor.extract(p, 2.0).to_dict(), "in_roi": [True]})
    lm.close()
    return cases


# ------------------------------------------------------------------ context
def context_cases() -> list:
    clock = {"t": 0.0}
    ctx = HttpContext("http://127.0.0.1:9/never", poll_s=3600, cfg=Config().context, clock=lambda: clock["t"])
    ctx.close()
    board = {"fix": True, "lat": 14.5649, "lng": 120.9932, "kmh": 31.5, "sats": 8, "hdop": 1.1, "jolt": 0.12, "tier": 0}
    script = []
    for i in range(30):
        t = float(i)
        reading = dict(board)
        if 10 <= i < 15:
            reading.update(fix=False, lat=0.0, lng=0.0, kmh=0.0)  # no satellite fix: 0 km/h must not mean "stopped"
        if i == 20:
            reading["motion_g"] = 0.5  # horizontal motion wins over jolt
        if 22 <= i < 27:
            reading["kmh"] = 2.0  # stopped
        script.append((t, reading if i not in (17, 18, 19, 23) else None))  # missing polls
    rows = []
    for t, reading in script:
        clock["t"] = t
        if reading is not None:
            ctx.ingest(reading, t=t)
        for dt in (0.0, 0.5):
            clock["t"] = t + dt
            r = ctx.read(t + dt)
            rows.append({"t": t + dt, "reading": reading if dt == 0 else None,
                         "expected": {"speed_kmh": r.speed_kmh, "motion_g": r.motion_g, "driving_s": r.driving_s,
                                      "speed_source": r.speed_source, "gps_age_s": r.gps_age_s,
                                      "latitude": r.latitude, "longitude": r.longitude}})
    return rows


# ------------------------------------------------------------------ fusion model
def fusion_cases() -> dict:
    """A small fusion model (MLP head + logistic head) trained on made-up numbers, and its outputs."""
    from dms.fusion import FEATURE_NAMES, FusionModel
    from dms.fusion.model import FORMAT
    from dms.fusion.train import fit_standardisation, train_network

    rng = np.random.default_rng(11)

    def sample(n):
        X = rng.uniform(0, 1, (n, len(FEATURE_NAMES)))
        X[:, 0:2] = rng.uniform(0.05, 0.4, (n, 2))  # EAR per eye
        X[:, 2] = rng.uniform(0.0, 0.9, n)  # MAR
        X[:, 3:6] = rng.normal(0, 15, (n, 3))  # head angles
        return np.round(X, 6)

    names = list(FEATURE_NAMES)
    X = sample(600)
    blink = (X[:, names.index("bs_eyeBlinkLeft")] + X[:, names.index("bs_eyeBlinkRight")]) / 2
    y_eye = ((X[:, 0] + X[:, 1]) / 2 < 0.2) | (blink > 0.75)
    y_yawn = (X[:, 2] > 0.5) & (X[:, names.index("bs_jawOpen")] > 0.3)
    mean, scale = fit_standardisation(X)
    Xs = (X - mean) / scale
    eye_layers, _ = train_network(Xs[:450], y_eye[:450].astype(float), Xs[450:], y_eye[450:].astype(float), hidden=8, seed=1, epochs=60)
    yawn_layers, _ = train_network(Xs[:450], y_yawn[:450].astype(float), Xs[450:], y_yawn[450:].astype(float), hidden=0, seed=1, epochs=60)
    data = {"format": FORMAT, "features": names, "mean": [round(v, 9) for v in mean.tolist()],
            "scale": [round(v, 9) for v in scale.tolist()],
            "heads": {"eye_closed": {"kind": "mlp", "layers": eye_layers}, "yawn": {"kind": "logistic", "layers": yawn_layers}}}
    data = json.loads(json.dumps(data))  # what the phone reads
    model = FusionModel(data)
    cases = []
    for row in sample(40):
        inputs = dict(zip(names, row.tolist()))
        cases.append({"inputs": inputs, "expected": model.predict(inputs)})
    missing = dict(cases[0]["inputs"])
    missing["yaw"] = None
    cases.append({"inputs": missing, "expected": model.predict(missing)})
    return {"model": data, "cases": cases}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--face-model", default="models/face_landmarker.task")
    ap.add_argument("--photos", default="data/sample_faces", help="folder with .jpg photos of faces (only their landmark numbers are saved)")
    ap.add_argument("--skip-features", action="store_true", help="no MediaPipe here: keep the existing features.json")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    n = 0
    with gzip.open(OUT / "engine.jsonl.gz", "wt", encoding="utf-8") as fh:
        for session in engine_sessions():
            fh.write(json.dumps(session) + "\n")
            n += len(session["frames"])
            print(f"  {session['name']}: {len(session['frames'])} frames")
    print(f"engine.jsonl.gz: {n} frames")
    if not args.skip_features:
        cases = feature_cases(Path(args.face_model), Path(args.photos))
        (OUT / "features.json").write_text(json.dumps(cases))
        print(f"features.json: {len(cases)} cases")
    (OUT / "context.json").write_text(json.dumps(context_cases()))
    (OUT / "fusion.json").write_text(json.dumps(fusion_cases()))
    # every desktop setting the phone must read (CoreTest.configJsonCoversEverySetting)
    (OUT / "config.json").write_text(json.dumps(android_config(None), indent=1))
    print("context.json written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
