# ESCA-3-2526-C3 Android app

The phone now runs the **same detection program as the laptop** (`dms/` in Python):

```
ESP32-CAM video  ->  MediaPipe face (478 points + 52 blendshapes)  +  YOLO .tflite (phone; built in)
                 ->  measurements (EAR, MAR, head angle)  [+ fusion model, if imported]
                 ->  calibration, behaviours, drowsiness state
                 ->  severity, fuzzy risk (81 rules), alert state machine
                 ->  buzzer on the sensor board  +  Firebase (supervisor)
```

The decision steps were translated line by line from the Python code. The tests in `app/src/test` replay
25 test drives (47,071 frames) and check that the phone's results are **identical** to the laptop's:
states, severity, risk, alert commands and calibration (see "Tests" below).

## 1. Build and install (Android Studio)

1. Install the latest **Android Studio**.
2. *File > Open* and pick the `androidapp` folder (not the repository root). Wait for "Gradle sync" to finish.
3. On the phone: *Settings > About phone*, tap *Build number* 7 times; then *Developer options > USB debugging* on.
4. Connect the phone with USB, choose it at the top of Android Studio, press **Run** (green triangle).
5. Two icons appear: **ESCA-3-2526-C3 Driver** (monitoring) and **ESCA-3-2526-C3 Supervisor**.

Phone: Android 7.0 or newer; a phone from the last ~5 years reaches 10-20 frames per second.

## 2. Connect to the boards

1. Flash and power both boards (`docs/HARDWARE_SETUP.md`, `MAKE_OWN_WIFI` / `JOIN_DMS_NET`).
2. Phone Wi-Fi: join **DMS-NET** (password `drivesafe1`). When Android says *"No internet"*, choose
   **Stay connected / Keep Wi-Fi**. Keep **mobile data on**: Firebase uses it while the Wi-Fi talks to the boards.
3. Driver app > **Settings**. The addresses are already the DMS-NET ones
   (camera `http://192.168.4.200`, sensor board `http://192.168.4.1`). Press:
   - **Test camera picture**: a photo from the ESP32-CAM appears.
   - **Test sensors**: GPS fix, speed, motion and "devices on DMS-NET".
   - **Test buzzer**: the buzzer sounds HIGH for 1.5 s.
   No sensor board? Clear its address and tick **Phone beeps too**.

## 3. Monitor

1. Driver app > **Start monitoring**. Close any browser tab showing the camera stream first
   (the ESP32-CAM serves one viewer at a time).
2. **Calibration**: look at the road normally for about 5 seconds. If the screen says the camera is too far to
   the side, follow the "move LEFT / UP ..." advice (same as `scripts/aim_camera.py`) and press **Recalibrate**.
3. The screen shows the live picture, the risk level, each behaviour, the alarm state and the raw measurements
   (EAR, MAR, yaw, pitch, fusion model outputs, speed, motion, FPS). The buzzer follows the same rules as the laptop.
4. **Stop monitoring** switches the buzzer off and saves the session log.

Long-press a behaviour row to send a **simulated** test event to Firebase (marked TEST); real events are marked
`source: device`.

## 4. Phone detection (YOLO)

**Built in:** `app/src/main/assets/yolo.tflite` is YOLOv8n trained on COCO (its class 67 is "cell phone"),
exported at 320 px in fp16 (6.4 MB), with `yolo_labels.txt`. Phone detection works right after install.
It finds phones held near the face well; small phones far from the camera are often missed.
YOLOv8 is published by Ultralytics under the **AGPL-3.0** license: fine for the thesis prototype; a public
release (e.g. Play Store) would need the app's source to be shared under the same license or an Ultralytics licence.

**Your own model** (the driver-state model: eye_open / eye_closed / yawn / phone) replaces it:

```bash
python scripts/export_models.py --models runs/detect/<name>/weights/best.pt --imgsz 320 --formats tflite_fp16
```

Copy `exports/android/<model>_320_tflite_fp16.tflite` and `exports/android/<model>_labels.txt` to the phone
(USB, Google Drive, ...). Then Driver app > Settings > **Import YOLO model** and **Import YOLO class names**.
*Remove imported files* goes back to the built-in model.
If the phone is slow, set **Run YOLO every N frames** to 2 or 3.

## 4a. Fusion model (optional)

A small trained network looks at all numbers of one picture together (EAR per eye, MAR, head angles, the 52
blendshapes) and gives P(eyes closed) and P(yawning); these are averaged with the other evidence (EAR, blendshapes,
YOLO) before the usual rules over time. Train it on the laptop (`scripts/build_fusion_dataset.py`, then
`scripts/train_fusion.py`), copy `models/fusion_model.json` to the phone, then Settings > **Import fusion model**.
The raw measurements then show its two outputs; without a model they show "Off".

## 5. Tuned settings (optional)

The phone starts with the thesis defaults (`configs/default.yaml`). After tuning on the laptop:

```bash
python scripts/export_android_config.py --config configs/<your tuned>.yaml
```

Copy `exports/android/dms_config.json` to the phone: Settings > **Import tuned config**.
*Remove imported files* goes back to the defaults.

## 6. Guided recording (labelled test data)

Driver app > **History** > **Start a guided recording**: participant code, day / night, script (standard ≈ 6 min or
quick ≈ 3 min). The app speaks each instruction with a 3-2-1 countdown ("Close your eyes … Now … Open your eyes"),
runs the normal detection program, and writes `labels.csv` + `guided.json` into the session folder, so the session
can be scored without labelling by hand. **Not done: remove label** drops the last action if the participant
missed it. Safety rules and the full procedure: [`docs/RECORDING_PROTOCOL.md`](../docs/RECORDING_PROTOCOL.md)
(record in a parked car or a simulator, never while really driving).

## 6a. Session logs (for the evaluation)

Each run is saved on the phone in the same format as the laptop (`frames.jsonl`, `alerts.jsonl`, `summary.json`).
Driver app > **History** > **Share session log** sends a .zip. On the laptop, unzip it and run:

```bash
python scripts/evaluate_behaviors.py --session <unzipped folder> --labels <unzipped folder>/labels.csv
python scripts/analyze_session.py <unzipped folder>
```

Logs contain measurements only, no pictures. Treat them as study data anyway (Data Privacy Act of 2012):
do not commit them.

## 7. Firebase (supervisor)

- Driver phone: sign in with the *driver* account. Live status (risk, behaviour, speed, GPS, alarm state) is
  sent every 2 s. Each alert is stored as an event. With no signal, events wait on the phone and are sent later.
- Supervisor phone: sign in with the *supervisor* account; Driver 1 shows the live data.
- Push notifications for real HIGH alerts (once per alert, at most one per minute) need the Cloud Function:
  `cd androidapp/firebase && firebase deploy --only functions`.

## 8. Tests

Android Studio: right-click `app/src/test/java` > **Run 'Tests in ...'** (or `./gradlew test`).

| Test | Checks |
|---|---|
| `GoldenParityTest` | 25 drives / 47,071 frames: every state, severity, risk value, tier and alert command equals the Python output (one drive includes fusion-model evidence); measurements from real MediaPipe landmarks; the fusion model's outputs (41 cases); GPS / motion context |
| `GuidedTest` | guided-recording schedule, spoken cues, "not done", early stop; the label files match the ones the Python evaluation reads (`tests/test_guided_labels.py`) |
| `CoreTest` | buzzer levels, the config file (every desktop setting is read), camera aiming, head-pose signs, numpy-style maths |
| `DeviceTest` | MJPEG stream reader, YOLO output decoding, buzzer queue (order, retry, off at the end), sensor polling, log format |

The expected values in `app/src/test/resources/golden/` come from the Python code. After changing the Python
decision logic or defaults, change the Kotlin files to match and regenerate them:

```bash
python scripts/export_android_golden.py --photos <folder with face photos>
```

## Code map

| Phone (Kotlin) | Laptop (Python) |
|---|---|
| `dms/Features.kt` | `dms/features/` (EAR, MAR, head pose) |
| `dms/Calibration.kt` | `dms/calibration/`, `dms/temporal/` |
| `dms/Behaviors.kt` | `dms/behaviors/` (quality, gaze, phone, yawning, drowsiness) |
| `dms/Risk.kt` | `dms/severity/`, `dms/risk/` (fuzzy logic) |
| `dms/Alerts.kt` | `dms/alerts/state_machine.py` |
| `dms/Engine.kt` | `dms/engine.py` |
| `dms/Context.kt` | `dms/context/` (GPS, motion, driving time), `dms/camera/aiming.py` |
| `dms/DeviceLink.kt` | `dms/alerts/sinks.py` (buzzer), `HttpContext` (sensor polling) |
| `dms/SessionLog.kt` | `dms/session_log.py` |
| `dms/Fusion.kt` | `dms/fusion/` (fusion model inputs and forward pass) |
| `dms/Guided.kt` | (phone only) guided-recording schedule and `labels.csv` (format of `dms/evaluation/labels.py`) |
| `monitor/` | Android parts: camera stream, MediaPipe, YOLO TFLite, Wi-Fi routing, the monitoring loop |

## Troubleshooting

| Problem | Fix |
|---|---|
| Gradle: *Could not find com.google.mediapipe:tasks-vision:...* | In `app/build.gradle.kts` use a newer version from maven.google.com (any 0.10.x) |
| Gradle: *Duplicate class ...* between MediaPipe and LiteRT (TensorFlow Lite) | Send the message; usually fixed with an `exclude` line in `app/build.gradle.kts` |
| Pop-up "This app isn't 16 KB compatible" | Fixed by the library versions in `app/build.gradle.kts` (MediaPipe 0.10.26, LiteRT 1.4.0, DataStore 1.3.0). If a library is still listed, check it in *Build > Analyze APK > lib/arm64-v8a* and raise that library's version |
| "Camera: ... retrying" | Phone on DMS-NET? Close browser tabs showing the stream; *Test camera picture* in Settings |
| Test buttons work but monitoring has no picture | Restart the ESP32-CAM (it may still be serving an old viewer) |
| Android leaves DMS-NET | Choose *Stay connected* on the "No internet" message; turn off *Switch to mobile data* / *Adaptive Wi-Fi* while testing |
| Low FPS | YOLO every 2-3 frames; keep the boards within a few metres |
| "Face model failed to load" | Rebuild: `app/src/main/assets/face_landmarker.task` must be in the app |
