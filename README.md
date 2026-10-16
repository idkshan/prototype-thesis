# Driver-Monitoring Detection Model — Version 2

Detection model for the thesis **"AI-Based Adaptive Driver-Monitoring System"** (ESP32-CAM + Android).
It detects **drowsiness, yawning, gaze deviation and mobile-phone use** from a driver-facing camera and
turns them into a **three-tier alarm** (soft buzzer / repeating buzzer / repeated alarm + push notification).

The model is a five-stage pipeline in which **AI models do the perception** and **transparent rules interpret
what the AI measured**:

```
 camera frame
     │
 ┌───▼─────────────────────────────────────────────────────────────────────────┐
 │ 1  AI-BASED IMAGE DETECTION (learned)                                        │
 │    MediaPipe Face Landmarker → 478 landmarks + 52 blendshapes                │
 │    YOLO (YOLOv8 / YOLO11 / YOLO26) → eye_open/eye_closed, yawn, phone boxes  │
 │    optional eye-state CNN (YOLO-cls trained on MRL Eye) → P(eyes closed)     │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │ 2  VISUAL FEATURE EXTRACTION (math)                                          │
 │    EAR, MAR, head pose (solvePnP), eyeBlink/jawOpen, phone-near-driver       │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │ 3  THRESHOLD-BASED CLASSIFICATION (rules, per-driver calibrated)             │
 │    evidence 0..1 (0.5 = driver's threshold) → eyes closed / mouth open /     │
 │    head away / phone visible → persistence in seconds → events, PERCLOS      │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │ 4  SEVERITY LEVEL per behaviour: NONE / MILD / MODERATE / SEVERE (0..1)      │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │ 5  OVERALL DRIVER RISK: fuzzy Fatigue F (Alert / Level 1-3; drowsy + yawning │
 │    together = one level higher), Distraction D, Context C → R 0..100         │
 │    → alarm tier NONE / LOW / MEDIUM / HIGH → buzzer + push commands          │
 └──────────────────────────────────────────────────────────────────────────────┘
         ▲ GPS speed, MPU6050 motion, trip duration (context)
```

**New here? Follow the step-by-step guide: [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)** (install → test → datasets → train → evaluate).
**Version 2 workflow (driver-state YOLO model, Android comparison, 30-participant study): [docs/GUIDE_v2.md](docs/GUIDE_v2.md).**
Mild / moderate / severe criteria and drowsiness levels: [docs/Severity_Criteria_Table.md](docs/Severity_Criteria_Table.md).

Thesis documentation: [Chapter 3 – Theoretical Considerations](docs/Chapter3_Theoretical_Considerations.md),
[Chapter 4 – Design Considerations](docs/Chapter4_Design_Considerations.md),
[Appendix – rule tables](docs/Appendix_Rule_Tables.md) (Word versions in `docs/word/`).

---

## 1. Installation

Requires **Python 3.10–3.12** (MediaPipe does not publish wheels for every newer version).

```bash
git clone https://github.com/idkshan/thesis-prototype.git
cd thesis-prototype
python -m venv .venv
# Windows: .venv\Scripts\activate      macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python scripts/download_models.py        # ~25 MB: face_landmarker.task, yolov8n.pt, yolo11n.pt, yolo26n.pt
```

*Linux only:* if you get `libEGL.so.1: cannot open shared object file`, install the system libraries:
`sudo apt-get install libegl1 libgles2 libgl1`.

Check that everything works (61 tests, about 20 s; the last one runs the real models):

```bash
python -m pytest
```

## 2. Run the detection model

```bash
# Laptop webcam (camera 0). A window shows the landmarks, evidence bars, severities and alarm tier.
python scripts/run_monitor.py --source 0
```

**First 5 seconds: calibration.** Look straight ahead with a relaxed face. The panel shows
`calibration: CALIBRATED` when your personal baseline is set. Press **c** to recalibrate (for example, a new driver), **q** to quit.

Try these in front of the camera:

| Do this | Expected result |
|---|---|
| Blink normally, talk | nothing |
| Close your eyes ~1 s | `LOW` (soft beep) |
| Close your eyes ~2 s | `LOW` → `MEDIUM` (repeating buzzer) |
| One big yawn (hold ~2 s) | `LOW` |
| Three yawns within 5 minutes | `MEDIUM` on the third |
| Look to the side / down for 2 s | `MEDIUM` |
| … for 3 s or longer | `HIGH` + push |
| Hold a phone near your face ≥ 3 s | `HIGH` + push |

Alerts are also printed in the terminal. Other sources and options:

```bash
# Compare phone detectors: just swap the weights file
python scripts/run_monitor.py --source 0 --yolo models/yolo11n.pt
python scripts/run_monitor.py --source 0 --yolo models/yolo26n.pt --imgsz 320

# Recorded video (uses video time, so it can run faster than real time); save an annotated copy
python scripts/run_monitor.py --source drive.mp4 --no-show --save-video annotated.mp4

# ESP32-CAM (CameraWebServer firmware): MJPEG stream or single-JPEG polling
python scripts/run_monitor.py --source http://192.168.4.1:81/stream
python scripts/run_monitor.py --source http://192.168.4.1/capture --fps 10

# Context and buzzer on the ESP32 (endpoints to be implemented in the firmware)
python scripts/run_monitor.py --source http://192.168.4.1:81/stream \
    --context-url http://192.168.4.1/sensors --alert-url http://192.168.4.1/alert

# Bench testing: fixed speed / motion, or pretend the trip started 2 hours ago
python scripts/run_monitor.py --source 0 --speed 60 --driving-minutes 120

# Ablation: geometric-only vs learned-only facial evidence
python scripts/run_monitor.py --source 0 --eye-mode ear --mouth-mode mar
python scripts/run_monitor.py --source 0 --eye-mode blendshape --mouth-mode blendshape
```

`--context-url` must return JSON such as `{"speed_kmh": 32.5, "motion_g": 0.08}`, where `motion_g` is the peak horizontal
acceleration (gravity removed) over the last second from the MPU6050. `--alert-url` receives
`?tier=HIGH&action=alarm&push=1`. Without GPS, the model assumes the car is moving, so a missing sensor never silences an alarm.

Every run writes `results/sessions/<name>/` with `frames.jsonl` (every stage, every frame), `alerts.jsonl`
and `summary.json` (FPS, per-stage latency median/p95, alarm episodes per hour, calibration baseline).

## 3. No camera? Simulate drivers

```bash
python scripts/simulate_scenarios.py                       # table: behaviour → alarms (Stages 3-5)
python scripts/simulate_scenarios.py --scenario phone_4s --plot phone.png   # stage-by-stage plot
python scripts/export_rule_tables.py                       # fuzzy rule tables + behaviour→alarm onsets
```

## 4. Experiments for the thesis

### 4.1 Train the driver-state detector (approved datasets only)

One YOLO model detects `eye_open`, `eye_closed`, `yawn` and `phone`. It is trained on the approved public datasets
(YawDD for eyes/yawns, State Farm / AUC for phones); the 30 participants are used **only** for validation and testing.

```bash
python scripts/build_driver_state_dataset.py --yawdd data/YawDD --phone-dataset datasets/phone_roboflow --out datasets/driver_state
#   -> review the boxes (Roboflow), then train all three families with identical settings:
python scripts/train_yolo.py --data datasets/driver_state/data.yaml \
    --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt --epochs 100 --imgsz 640 --device 0
```

### 4.2 YOLOv8 vs YOLO11 vs YOLO26: desktop and Android

```bash
# accuracy per class (P, R, F1, AP50) + desktop speed/size
python scripts/benchmark_yolo.py --models runs/detect/yolov8n/weights/best.pt runs/detect/yolo11n/weights/best.pt \
    runs/detect/yolo26n/weights/best.pt --data datasets/driver_state/data.yaml --split test --imgsz 320 640 --formats pytorch onnx
# export for Android, then measure ON the phone over ADB (CPU / GPU / NNAPI, latency, memory, temperature)
python scripts/export_models.py --models <three best.pt> --imgsz 320 640 --formats tflite tflite_fp16 tflite_int8 \
    --data datasets/driver_state/data.yaml
python scripts/android_benchmark.py --models exports/android --delegates cpu gpu nnapi --merge results/yolo_benchmark/results.csv
```

Results: `results/yolo_benchmark/` (desktop) and `results/android_benchmark/` (phone). The decision rule was fixed
**before** testing (Chapter 4, Section 4.4.4). TFLite export needs TensorFlow (`pip install tensorflow`, or use Colab).

### 4.3 Use the trained model in the pipeline (AI + thresholds)

```bash
python scripts/run_monitor.py --source 0 --yolo runs/detect/yolo11n/weights/best.pt
```

The YOLO eye/yawn boxes become a third evidence source next to the geometric (EAR/MAR) and blendshape sources; the
thresholds and durations then decide whether it is a behaviour and how severe it is.

### 4.4 Optional learned eye-state model (MRL Eye Dataset)

```bash
python scripts/download_models.py --cls
python scripts/prepare_mrl_eye.py --src data/mrlEyes_2018_01 --out datasets/mrl_eye
python scripts/train_yolo.py --task classify --data datasets/mrl_eye \
    --models models/yolov8n-cls.pt models/yolo11n-cls.pt models/yolo26n-cls.pt --imgsz 96 --epochs 30 --device 0
python scripts/run_monitor.py --source 0 --eye-model runs/classify/yolo11n-cls/weights/best.pt
```

### 4.5 Participant study: validation and test (SO3, SO5)

Label each recording as a CSV (`behavior,start_s,end_s`; see `docs/templates/`), list the sessions in a manifest
with their split (`validation` / `test`, one split per participant) and condition (day / night), then:

```bash
python scripts/process_videos.py --videos data/study/videos --yolo <best.pt>
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split validation --replay --config configs/tuned.yaml --tag tuned
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --replay --config configs/tuned.yaml --tag final
# ablation: AI only vs thresholds only vs both
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --replay --eye-mode yolo --mouth-mode yolo --tag ai_only
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --replay --eye-mode ear --mouth-mode mar --tag thresholds_only
```

This reports pooled event- and frame-level P/R/F1 per behaviour against the Table 1.3 targets, per-participant spread,
detection delay, unnecessary alarms per hour (target ≤ 0.5/h), and the drowsiness-level confusion matrix. For a
single session use `scripts/evaluate_behaviors.py --session ... --labels ...`.

## 5. Tuning

All thresholds live in [`configs/default.yaml`](configs/default.yaml), with a comment explaining each one. Copy it, change
values, and pass `--config configs/my.yaml` to any script. Only the keys you change need to be in your copy. Unknown
keys are rejected, so typos are caught. Tune on a *tuning* split and report final numbers on sessions that were not used for tuning.

## 6. Project structure

```
dms/                         the detection model (Python package)
  perception/                Stage 1  face_landmarker.py, object_detector.py (YOLO), eye_state.py
  features/                  Stage 2  geometry.py (EAR, MAR), head_pose.py (solvePnP), extractor.py
  classification/            Stage 3  calibration.py, evidence.py, temporal.py, classifier.py
  severity.py                Stage 4
  risk/                      Stage 5  fuzzy.py (Mamdani engine), rules.py (81 rules), driver_risk.py
  alerts.py                  three-tier alarm manager (+ ESP32 HTTP sink)
  engine.py                  Stages 3-5 chained (camera-independent)
  monitor.py                 Stages 1-5 for one frame
  context.py, sources.py     GPS/MPU6050 context; webcam / video / ESP32-CAM input
  config.py, types.py        all parameters; data passed between stages
  simulation.py, overlay.py, session_log.py
scripts/                     run_monitor, download_models, build_driver_state_dataset, autolabel_phones,
                             prepare_mrl_eye, train_yolo, benchmark_yolo, export_models, android_benchmark,
                             process_videos, evaluate_behaviors, evaluate_study, simulate_scenarios,
                             export_rule_tables, make_figures
configs/default.yaml         every tunable number, commented
tests/                       tests (formulas, fuzzy rules, calibration, scenarios, YOLO integration, metrics, study, end-to-end)
docs/                        Chapter 3, Chapter 4, appendix, criteria table, guides, templates/, figures, Word exports
```

## 7. How the pipeline decides (short walkthrough)

1. **Stage 1 (AI).** MediaPipe's neural networks find the face, place 478 landmarks and score 52 facial actions
   (e.g. `eyeBlinkLeft`, `jawOpen`). YOLO finds phones. These are learned models, not thresholds.
2. **Stage 2 (features).** From the landmarks: EAR (eye openness), MAR (mouth openness) and head yaw/pitch/roll
   (solvePnP). Phones far from the driver's face are ignored.
3. **Stage 3 (threshold-based classification).** During the first 5 s the model learns *your* normal EAR, MAR and forward head
   pose. Each signal becomes *evidence* from 0 (your normal) to 1, where **0.5 = your personal threshold** (e.g. EAR below
   70 % of your normal). Geometric and learned evidence are averaged. A state must then **persist**: closures longer
   than a blink (0.5 s), mouth wide open ≥ 1.5 s (a yawn, not speech), looking away ≥ 2 s (not a mirror check), phone ≥ 3 s.
4. **Stage 4 (severity).** Durations and counts become a severity score per behaviour, e.g. looking away 2 s → MODERATE,
   3 s → SEVERE.
5. **Stage 5 (risk).** Fuzzy logic combines the severities into Fatigue (eyes, yawns, trip duration), Distraction
   (gaze, phone) and Context (speed, erratic motion), then into one risk score R (0–100) → alarm tier. Being stopped lowers
   the tier; erratic motion raises it to HIGH. The alarm escalates immediately but releases only after 3 s, and repeats are
   rate-limited to avoid alarm fatigue.

Chapter 4 contains the full parameter tables, the rationale behind each value and the verification results.

## 8. Privacy

Session logs contain facial measurements, and recorded videos contain faces. `.gitignore` excludes `*.mp4`, `results/`
and `datasets/`. Handle participant data according to Section 1.7.20 (Data Privacy Act of 2012) and delete it when the study ends.
