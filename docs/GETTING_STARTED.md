# Getting Started: Install, Datasets, Training, Testing

A step-by-step guide to the detection model, from installing it to producing the numbers for your thesis.
> **Version 2:** Phases 1–3 still apply. For training the driver-state YOLO model (eyes, yawns, phones), comparing
> YOLOv8 / YOLO11 / YOLO26 on desktop **and on the Android phone**, and the 30-participant validation/test study,
> follow **[GUIDE_v2.md](GUIDE_v2.md)**; it replaces Phases 4 and 6–8 below.

Follow the phases in order. **Phases 1–2 need no datasets at all.** The model works out of the box with
pretrained weights, so you can test it on your webcam today. Datasets are needed only for Phases 3–7.

| Phase | What you do | Needs datasets? | Needs GPU? |
|---|---|---|---|
| 1 | Install | No | No |
| 2 | Test the model (tests, simulation, webcam) | No | No |
| 3 | Download and place the datasets | – | No |
| 4 | Build the phone dataset, train YOLOv8/11/26, compare them | State Farm (+ AUC) | Yes (or Colab) |
| 5 | Train the optional eye-state model | MRL Eye | Recommended |
| 6 | Record and label your own test videos | Your own recordings | No |
| 7 | Evaluate accuracy (SO3), false alarms (SO5), ablation, tuning | Your recordings, YawDD, SynDD1 | No |
| 8 | Phone (Android) benchmark | Exported models | No |

---

## Phase 1: Install

**You need:** Python 3.10–3.12, Git, and a laptop with a webcam. Windows, macOS and Linux all work.

```bash
git clone https://github.com/idkshan/thesis-prototype.git
cd thesis-prototype
git checkout claude/bold-volta-j8i7v3        # the branch with the model (until it is merged)

python -m venv .venv
# Windows:      .venv\Scripts\activate
# macOS/Linux:  source .venv/bin/activate

pip install -r requirements.txt
python scripts/download_models.py            # downloads into models/: face_landmarker.task, yolov8n.pt, yolo11n.pt, yolo26n.pt
```

*Linux only:* if you see `libEGL.so.1: cannot open shared object file`, run
`sudo apt-get install libegl1 libgles2 libgl1`.

---

## Phase 2: Test the model (no datasets needed)

### 2.1 Automated tests

```bash
python -m pytest
```

All **61 tests** should pass (about 20 s). The last test runs the real neural networks on a sample photo.
If it is skipped, the models were not downloaded (repeat the last command of Phase 1).

### 2.2 Simulated drivers (checks the threshold and fuzzy logic without a camera)

```bash
python scripts/simulate_scenarios.py
```

This prints a table of behaviours and the alarms they trigger (e.g. "Phone use 4 s → HIGH + push").

### 2.3 Live test on your webcam

```bash
python scripts/run_monitor.py --source 0
```

1. **Calibration (first 5 s):** look straight at the road/screen with a relaxed face. The side panel changes to
   `calibration: CALIBRATED`.
2. Try the behaviours below and watch the panel and the terminal:

| Do this | Expected alarm |
|---|---|
| Blink normally, talk | none |
| Close your eyes ~1 s | LOW (soft beep) |
| Close your eyes ~2 s | LOW → MEDIUM |
| One big yawn (mouth wide open ~2 s) | LOW |
| Three yawns within 5 min | MEDIUM on the third |
| Look to the side or down for 2 s | MEDIUM |
| … for 3 s or longer | HIGH + push |
| Hold a phone near your face ≥ 3 s | HIGH + push |

3. Keys: **c** = recalibrate, **q** = quit.
4. Try the other detectors: `--yolo models/yolo11n.pt` or `--yolo models/yolo26n.pt`.

After each run, open `results/sessions/<session name>/summary.json`. It shows the frame rate, the time each stage
took, your calibration values and the number of alarms.

**Other sources:**

```bash
python scripts/run_monitor.py --source myvideo.mp4                       # a recorded video
python scripts/run_monitor.py --source http://<ESP32-IP>:81/stream       # ESP32-CAM (CameraWebServer firmware)
python scripts/run_monitor.py --source http://<ESP32-IP>/capture --fps 10
```

---

## Phase 3: Datasets

### 3.1 Which datasets, and what each one is for

| Dataset | Used for | Required? | Where to get it | Licence note |
|---|---|---|---|---|
| **State Farm Distracted Driver Detection** | Training the **phone detector** (YOLOv8/11/26 comparison) | **Yes**, for Phase 4 | Kaggle competition page: https://www.kaggle.com/c/state-farm-distracted-driver-detection (log in, accept the rules, download `imgs.zip` and `driver_imgs_list.csv`) | Competition rules: research/non-commercial use |
| **AUC Distracted Driver** | Extra phone-detector training data | Optional | Request access from the dataset authors (Eraqi et al., American University in Cairo) through their request form | Research use by request |
| **MRL Eye Dataset** | Training the optional **eye-state classifier** | Optional (Phase 5) | http://mrl.cs.vsb.cz/eyedataset | Academic use; cite Fusek (2018) |
| **YawDD** | Validating **yawning** detection (MAR + jawOpen) | Recommended (Phase 7) | IEEE DataPort, "YawDD: Yawning Detection Dataset" (Abtahi et al., 2014); free account needed | Research use |
| **SynDD1** | Validating **gaze deviation** | Recommended (Phase 7) | Linked from the *Data in Brief* article by Rahman et al. (2023) | Research use |
| **Your own ESP32-CAM / webcam recordings** | **Final test set** for all four behaviours and the phone detector | **Yes**, for the thesis results | You record them (Phase 6) | Consent forms; Data Privacy Act |
| COCO | Already built into the pretrained YOLO weights | Nothing to download | – | – |

Why your own recordings are required: State Farm and AUC photograph the driver **from the passenger side**, and
MRL contains only **cropped eyes**. None of them looks like the windshield view of the ESP32-CAM, so final
accuracy must be measured on footage from your own camera (Chapter 4, §4.4.3 and §4.11).

### 3.2 Where to put them

Put the downloaded files in a folder called `data/`. It is ignored by Git, so datasets are never uploaded. The
scripts write their prepared versions to `datasets/`, which is also ignored.

```
thesis-prototype/
├── data/                                  <- YOU put the raw downloads here
│   ├── state-farm/
│   │   ├── imgs/train/c0 … c9/*.jpg       (unzipped imgs.zip; keep the c0..c9 folders)
│   │   └── driver_imgs_list.csv
│   ├── auc/                               (optional; same c0..c9 folder structure)
│   ├── mrlEyes_2018_01/                   (unzipped MRL; subfolders s0001, s0002, …)
│   ├── yawdd/                             (videos, e.g. Dash/Male/*.avi)
│   ├── syndd1/                            (videos)
│   └── recordings/                        (your own videos: S01.mp4, S02.mp4, …)
├── labels/                                <- YOU create these CSVs (Phase 6)
│   └── S01.csv, S02.csv, …
├── datasets/                              <- created by the scripts (Phases 4–5)
│   ├── phone/      images/ labels/ data.yaml
│   └── mrl_eye/    train/ val/ test/
├── runs/                                  <- trained weights (Phases 4–5)
└── results/                               <- session logs and benchmark tables
```

---

## Phase 4: Phone detector (YOLOv8 vs YOLO11 vs YOLO26)

### 4.1 Build a detection dataset from State Farm

State Farm has only image-level labels (c0 = safe driving, c1–c4 = texting or talking on the phone), with no boxes
around the phone. This script uses a large "teacher" YOLO model to draw the boxes automatically. It splits the data
**by driver**, so the same person never appears in both training and test data.

```bash
python scripts/download_models.py --sizes x        # teacher weights (yolov8x, yolo11x, yolo26x), ~100 MB each

python scripts/autolabel_phones.py \
    --src data/state-farm/imgs/train \
    --driver-csv data/state-farm/driver_imgs_list.csv \
    --out datasets/phone \
    --teacher models/yolo11x.pt
```

It prints how many images and boxes went into train/val/test. It also writes `datasets/phone/needs_review.txt`,
which lists phone images where the teacher found no phone.

### 4.2 Check the labels (required before reporting results)

Open `datasets/phone` in a labelling tool (**Roboflow**, **CVAT** or **Label Studio**; all accept YOLO format).
Correct wrong or missing boxes, **at least in the `test` split**. If you skip this, the test only measures agreement
with the teacher model, not true accuracy.

### 4.3 Train all three detectors with identical settings

Training needs a GPU. If your laptop has no NVIDIA GPU, use Google Colab (see the end of this guide).

```bash
python scripts/train_yolo.py --data datasets/phone/data.yaml \
    --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt \
    --epochs 100 --imgsz 640 --batch 16 --device 0
```

Trained weights are saved to `runs/detect/<model>/weights/best.pt`. A side-by-side summary is saved to
`runs/detect/training_summary.json`.

### 4.4 Compare them

```bash
pip install onnx onnxruntime        # for the ONNX format
python scripts/benchmark_yolo.py \
    --models runs/detect/yolov8n/weights/best.pt runs/detect/yolo11n/weights/best.pt runs/detect/yolo26n/weights/best.pt \
    --data datasets/phone/data.yaml --split test \
    --imgsz 320 640 --formats pytorch onnx ncnn
```

The results go to `results/yolo_benchmark/results.md`: size, parameters, GFLOPs, latency (including NMS), FPS,
precision, recall, F1 and AP50. Copy them into **TABLE 4.7** of Chapter 4.
For TFLite (Android) formats, also run `pip install tensorflow` and add `tflite_fp16 tflite_int8` to `--formats`.

Choose the winner with the rule already written in Chapter 4, §4.4.5 (decided before testing). Then use it in the model:
`python scripts/run_monitor.py --source 0 --yolo runs/detect/<winner>/weights/best.pt`

---

## Phase 5: Eye-state model (optional, strengthens the AI for drowsiness)

```bash
python scripts/download_models.py --cls                       # yolov8n-cls, yolo11n-cls, yolo26n-cls
python scripts/prepare_mrl_eye.py --src data/mrlEyes_2018_01 --out datasets/mrl_eye
python scripts/train_yolo.py --task classify --data datasets/mrl_eye \
    --models models/yolov8n-cls.pt models/yolo11n-cls.pt models/yolo26n-cls.pt \
    --imgsz 96 --epochs 30 --device 0
```

Accuracy (top-1) for each model is in `runs/classify/training_summary.json`. Use the best one in the pipeline:

```bash
python scripts/run_monitor.py --source 0 --eye-model runs/classify/yolo11n-cls/weights/best.pt
```

When enabled, the eye decision averages three signals: EAR, MediaPipe's `eyeBlink` score and this classifier.

---

## Phase 6: Record and label your own test videos

### 6.1 Record

Record **raw** video, without the overlay, with the camera in its real position: the ESP32-CAM on the windshield,
or a phone or webcam at the same angle. Suggested script for each participant (about 5 minutes):

1. 30 s: look ahead normally (include blinking and talking)
2. 3 yawns, spaced out
3. Eye closures of about 1 s and about 2 s
4. Mirror glances (about 1 s), then looking away 3–4 s, then looking down 3–4 s
5. Phone: pick it up briefly (< 2 s), then use it for about 5–10 s
6. 60 s: normal driving posture (for the false-alarm count)

Record in both **day and night** conditions, since your field test uses both. Get consent first (Section 1.7.20).
Save the files as `data/recordings/S01.mp4`, `S02.mp4`, ….

### 6.2 Label

Watch each video in any video player and write down when each behaviour starts and ends, in seconds from the start
of the video. Save this as `labels/S01.csv`:

```csv
behavior,start_s,end_s
yawning,35.2,40.1
drowsiness,62.0,64.1
gaze_deviation,80.5,84.0
phone_usage,101.3,110.8
```

Allowed behaviour names: `drowsiness`, `yawning`, `gaze_deviation`, `phone_usage`. The Table 1.11 labels
`slightly_drowsy` and `moderately_drowsy` are also accepted and counted as drowsiness.
Rows for periods with no risky behaviour are not needed. For YawDD and SynDD1, write label CSVs the same way.

### 6.3 Run the model on each recording

```bash
python scripts/run_monitor.py --source data/recordings/S01.mp4 --session-name S01 --no-show
```

Recorded files are processed using the video's own time, so the model's times line up with your labels.

---

## Phase 7: Evaluate

### 7.1 Accuracy (SO3) and false alarms (SO5)

```bash
python scripts/evaluate_behaviors.py --session results/sessions/S01 --labels labels/S01.csv --out results/eval/S01.json
```

For each behaviour you get precision, recall and F1 (frame level and event level), with the target (≥ 0.85 or ≥ 0.80),
the detection delay, and **unnecessary alarms per hour** (target ≤ 0.5/h).

### 7.2 Does the AI improve accuracy? (ablation)

Re-score the *same* session three ways. The neural networks do not need to run again.

```bash
python scripts/evaluate_behaviors.py --session results/sessions/S01 --labels labels/S01.csv --replay --eye-mode ear        --mouth-mode mar         # thresholds only
python scripts/evaluate_behaviors.py --session results/sessions/S01 --labels labels/S01.csv --replay --eye-mode blendshape --mouth-mode blendshape  # AI only
python scripts/evaluate_behaviors.py --session results/sessions/S01 --labels labels/S01.csv --replay --eye-mode fused      --mouth-mode fused       # combined
```

If you trained the eye model, record sessions with `--eye-model …` and also try `--eye-mode cnn`.
Compare the drowsiness and yawning F1 across the three lines. That is the evidence for your adviser.

### 7.3 Tuning thresholds

1. Copy `configs/default.yaml` to `configs/tuned.yaml` and change values. Every value has a comment explaining it.
2. Re-score quickly: `python scripts/evaluate_behaviors.py --session … --labels … --replay --config configs/tuned.yaml`
3. **Tune on some sessions (e.g. S01–S05) and report final numbers on the others (S06+).** Otherwise the
   thresholds are fitted to the test data.
4. Use the tuned file live: `python scripts/run_monitor.py --source 0 --config configs/tuned.yaml`

---

## Phase 8: Android benchmark

Export the chosen detector (`benchmark_yolo.py --formats tflite_fp16 tflite_int8 ncnn` writes the exports into
`results/yolo_benchmark/exports/`). Then follow the on-phone protocol in **Chapter 4, §4.4.4**: the TFLite
`benchmark_model` tool over ADB, in-app timing, and a 30-minute battery and heat check. Fill in the phone columns of TABLE 4.7.

---

## Training on Google Colab (free GPU)

1. Upload the prepared `datasets/phone` folder (after Phase 4.1–4.2) to your Google Drive.
2. In a new Colab notebook, choose *Runtime → Change runtime type → GPU*, then run:

```python
from google.colab import drive; drive.mount('/content/drive')
!git clone -b claude/bold-volta-j8i7v3 https://github.com/idkshan/thesis-prototype.git
%cd thesis-prototype
!pip install -q -r requirements.txt
!python scripts/download_models.py
!cp -r /content/drive/MyDrive/datasets .          # your uploaded datasets folder
!sed -i "s#^path:.*#path: /content/thesis-prototype/datasets/phone#" datasets/phone/data.yaml
!python scripts/train_yolo.py --data datasets/phone/data.yaml --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt --epochs 100 --device 0
!cp -r runs /content/drive/MyDrive/                # save the trained weights
```

The `sed` line is needed because `data.yaml` stores the folder path of the computer where it was created.
Download `runs/` from Drive into your local project folder afterwards.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `libEGL.so.1` error (Linux) | `sudo apt-get install libegl1 libgles2 libgl1` |
| Webcam does not open | Try `--source 1`; on macOS allow camera access for the terminal app |
| Stuck on `CALIBRATING` | Face the camera, keep your head still for 5 s, and put the phone away |
| Many gaze alarms right at the start | Wait for `CALIBRATED`, or press **c** to recalibrate facing forward |
| Slow (< 10 FPS) | `--imgsz 320` and/or `--yolo-every 2` |
| No face detected at night | Add light or IR LEDs (see Chapter 4, TABLE 4.18 item 7) |
| `No phone class found` in the benchmark | Pass `--gt-classes 0` |
| Training uses the CPU | Install the CUDA version of PyTorch, or use Colab |

## Checklist for the thesis results

- [ ] Phase 2 done: tests pass, webcam test behaves as in the table
- [ ] State Farm downloaded, phone dataset built, **test labels reviewed**
- [ ] Three detectors trained; `results/yolo_benchmark/results.md` produced; winner chosen by §4.4.5
- [ ] (Optional) eye-state model trained on MRL
- [ ] Recordings made (day and night) and labelled; `evaluate_behaviors.py` run on each
- [ ] Ablation (thresholds only vs AI only vs combined) table produced
- [ ] Thresholds tuned on tuning sessions; final numbers from held-out sessions
- [ ] Android benchmark of the chosen detector
