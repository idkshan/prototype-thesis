# Driver-Monitoring Detection Model, Version 2: Step-by-Step Guide

**Train, compare on desktop and Android, and validate with the 30 participants.**

_AI-Based Adaptive Driver-Monitoring System (ESP32-CAM + Android)_ · Repository: github.com/idkshan/thesis-prototype  (branch claude/bold-volta-j8i7v3)

A Word version of this guide was shared separately. Thesis criteria table: [Severity_Criteria_Table.md](Severity_Criteria_Table.md).

## 1. What Changed in Version 2

Version 2 implements your adviser's four requests:

| Adviser's request | What the code does now | Where |
|---|---|---|
| Keep the thresholds, but integrate them with AI: the AI identifies the behaviour, the thresholds set the severity | One YOLO **driver-state model** detects `eye_open`, `eye_closed`, `yawn` and `phone` in every frame. Its boxes become AI evidence next to EAR/MAR and MediaPipe's blendshapes. The thresholds then decide how long a behaviour must last and how severe it is (MILD / MODERATE / SEVERE). | `dms/features/extractor.py`, `dms/classification/classifier.py`, `dms/severity.py` |
| Compare YOLOv8, YOLO11 and YOLO26, including after deployment on Android | Train all three with identical settings, measure accuracy per class on the desktop, export them to TFLite, and measure them **on the Android phone** (speed, memory, temperature) with Google's official TFLite benchmark tool over USB. | `train_yolo.py`, `benchmark_yolo.py`, `export_models.py`, `android_benchmark.py` |
| Combine detections: drowsy and yawning together = higher risk | The fatigue rules now have four levels: Alert, Level 1 (slightly drowsy), Level 2 (moderately drowsy), Level 3 (severely drowsy). When eye closure AND yawning are both present, the level rises by one. 81 fuzzy rules in total. | `dms/risk/rules.py`, `docs/Severity_Criteria_Table.md` |
| Train on the approved datasets; use the 30 participants for validation and testing | The YOLO model is trained only on YawDD, State Farm (+ AUC, SynDD1 frames). The participants are split by person into validation (to tune) and test (to report). One command evaluates a whole split, with day/night results and the drowsiness-level confusion matrix. | `build_driver_state_dataset.py`, `process_videos.py`, `evaluate_study.py` |

### 1.1 AI part vs. rule part

| Step | Done by | Type |
|---|---|---|
| Find the face, eyes, mouth; read the facial expression | MediaPipe Face Landmarker (478 landmarks, 52 blendshapes) | AI |
| Is the eye closed? Is it a yawn? Is a phone in hand? | YOLO driver-state model (YOLOv8 / YOLO11 / YOLO26, your trained winner) | AI |
| Eye openness (EAR), mouth openness (MAR), head angle | Geometry on the landmarks | Math |
| Is it a real behaviour, or a blink / speech / mirror check? | Personal calibration + minimum durations | Thresholds |
| How serious is it? MILD / MODERATE / SEVERE | Severity tables | Thresholds |
| How drowsy overall (Level 1-3)? Which alarm? | Mamdani fuzzy logic (81 rules) + speed / motion / driving time | Fuzzy rules |

> **Important:** Your adviser's sentence maps directly onto this table: rows 1-2 **identify** the behaviour (AI), rows 4-5 **set the severity** (thresholds), row 6 **combines** the detections (drowsy + yawning = higher risk).

### 1.2 Your road map

| Part | What you do | Where | Time |
|---|---|---|---|
| A | Update the code on your laptop | Laptop | 15 min |
| B | Put the approved datasets in place | Laptop | 1-2 h (downloads) |
| C | Phone dataset: State Farm + SynDD1 frames, checked in Roboflow | Laptop + Roboflow | Several hours (checking) |
| D | Build the driver-state dataset (eyes, yawns, phones) and check it | Laptop + Roboflow | 1-3 h |
| E | Train YOLOv8n, YOLO11n, YOLO26n (identical settings) | Google Colab (GPU) | 3-6 h |
| F | Compare the three on the desktop (accuracy per class) | Laptop | 15 min |
| G | Compare the three on the Android phone | Laptop + phone (USB) | 30-60 min |
| H | Plug the winner into the program | Laptop | 5 min |
| I | 30-participant study: record, label, validate, test | Car / laptop | Most of the work |
| J | Criteria table for the thesis (mild / moderate / severe) | Thesis | - |

## Part A. Update the Code

You downloaded the code as a ZIP before. Download the new version the same way, and keep your data.

**Step 1.** Open **github.com/idkshan/thesis-prototype** in your browser, switch the branch selector to **claude/bold-volta-j8i7v3**, then click **Code → Download ZIP**.

**Step 2.** Unzip it next to your old folder. Then **move these folders from the old folder into the new one** (they are not in the ZIP): `.venv`, `models`, `data`, `datasets`, `runs`, `results`, `labels` (whichever you have).

**Step 3.** Open the new folder in **VS Code** (File → Open Folder) and open a terminal (**Terminal → New Terminal**). It opens in PowerShell.

**Step 4.** Activate the virtual environment, update the libraries and download any missing models:

```
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python scripts/download_models.py
```

> **Tip:** No `.venv` folder yet, or it was made with Python 3.13/3.14? Create it with Python 3.12 (install it from python.org first): `py -3.12 -m venv .venv`, activate it, then run the two commands above. MediaPipe and PyTorch need Python 3.10-3.12.

**Step 5.** Check that everything works:

```
python -m pytest
```

> **You should see:** `72 passed` (a few may say `skipped` if a model file is missing; run `python scripts/download_models.py`).

## Part B. The Approved Datasets

Only the approved datasets are used for **training**. The 30 participants are **never** used for training; they are used for validation and testing (Part I).

| Dataset | What it gives the model | Used in | Needed? |
|---|---|---|---|
| **YawDD** (IEEE DataPort) | Faces of drivers yawning, talking and driving normally → open/closed eye boxes and yawn boxes | Part D | Yes |
| **State Farm** Distracted Driver (Kaggle) | Drivers texting / calling → phone boxes (passenger-side view) | Part C | Yes |
| **SynDD1** (Data in Brief, Rahman et al.) | Dashboard-camera videos with phone use → phone boxes from YOUR camera angle | Part C | Recommended |
| **AUC** Distracted Driver | More phone photos (same classes as State Farm) | Part C | Optional |
| **MRL Eye** | Cropped eye images → optional extra eye-state classifier | Optional (old guide Part F) | Optional |
| **COCO** | Already inside the pretrained YOLO weights (`yolov8n.pt` etc.). Nothing to download. | - | Built in |

Put the downloads in the `data` folder like this:

```
thesis-prototype/
├── data/
│   ├── yawdd/          (unzip YawDD here: the Mirror and Dash folders with the .avi videos)
│   ├── state-farm/     (imgs/train/c0..c9 and driver_imgs_list.csv)
│   ├── syndd1/         (the Dashboard videos; Rear-view / Right-side not needed)
│   ├── auc/            (optional)
│   └── study/          (Part I: participant videos, labels, manifest)
├── datasets/          (created by the scripts)
├── runs/              (trained models)
├── exports/           (Android model files)
└── results/           (all tables and reports)
```

> **Important:** The `data`, `datasets`, `runs`, `exports` and `results` folders, and every `.mp4` / `.avi` file, are ignored by Git. Participant videos are never uploaded to GitHub (Data Privacy Act of 2012).

## Part C. Phone Dataset (State Farm + SynDD1)

You already did the State Farm part (old guide, Part E.1-E.2: `autolabel_phones.py`, then checking the boxes in Roboflow). If not, do it now:

```
python scripts/download_models.py --sizes x
python scripts/autolabel_phones.py --src data/state-farm/imgs/train --driver-csv data/state-farm/driver_imgs_list.csv --out datasets/phone --teacher models/yolo11x.pt
```

### C.1 Add dashboard-view phone frames (camera-angle fix)

Your adviser noted that the State Farm camera looks at the driver from the side, while the ESP32-CAM looks from the front. SynDD1 has dashboard-camera videos, so adding some of its phone frames teaches YOLO what a phone looks like from your angle.

**Step 1.** Save one frame every 2 seconds from the SynDD1 dashboard videos:

```
python scripts/extract_frames.py --videos data/syndd1/Dashboard --out data/syndd1_frames --every-sec 2
```

> **Tip:** To keep only the phone parts, make a CSV `video,start_s,end_s` from the SynDD1 annotation files (the phone-call and texting rows) and add `--ranges data/syndd1_phone_ranges.csv --every-sec 1`.

**Step 2.** In **Roboflow**, open your phone project and **upload** the `data/syndd1_frames` images (300-1000 frames is enough; include frames WITHOUT a phone too).

**Step 3.** Draw a box around every phone, class name `phone` (the same name as your State Farm boxes). Roboflow's **Label Assist** can pre-draw boxes for you; check each one.

**Step 4.** When splitting into train/valid/test, keep **each SynDD1 driver in one split only** (the driver id is in the file name).

**Step 5.** **Export** the whole project: Format **YOLOv8**, "Download zip to computer". Unzip it to `datasets/phone_roboflow` (it contains `train`, `valid`, `test` and `data.yaml`).

> **You should see:** `datasets/phone_roboflow/train/images`, `.../valid/images`, `.../test/images` and a `data.yaml` listing `phone`.

## Part D. Build the Driver-State Dataset

This makes **one** dataset with four classes, so **one** YOLO model detects everything in a single pass (important for phone speed):

| Class | Comes from | How the boxes are made |
|---|---|---|
| `eye_closed`, `eye_open` | YawDD videos + the phone photos | MediaPipe finds the eyes; EAR + eyeBlink compared with the person's normal values decide open/closed. Unclear eyes are skipped, not guessed. |
| `yawn` | YawDD videos (the Yawning ones) | Mouth box when MAR + jawOpen are clearly wide open. Never labelled in the Normal / Talking videos. |
| `phone` | Your checked Roboflow phone export | Your boxes are copied unchanged. |

**Step 1.** Run the builder (takes 30-90 minutes; it analyses every YawDD video):

```
python scripts/build_driver_state_dataset.py --yawdd data/yawdd --phone-dataset datasets/phone_roboflow --out datasets/driver_state
```

> **You should see:** A table with the number of images and boxes per class for train / val / test, `datasets/driver_state/data.yaml`, and `review_eyes.txt` (phone photos with an unclear eye).

> **Tip:** YawDD is split **by person**, so nobody appears in both training and test. Closed eyes and yawns are rare, so those frames are saved 5 times more often than ordinary frames.

### D.1 Check the boxes in Roboflow

**Step 1.** Create a **new** Roboflow project (Object Detection), e.g. `driver-state`.

**Step 2.** Upload the `datasets/driver_state` folder. Roboflow recognises the YOLO labels and the train/val/test folders.

**Step 3.** Check **at least every test image**: wrong open/closed labels, yawn boxes on talking mouths, missing phones. Also look at the photos listed in `review_eyes.txt`.

**Step 4.** Export as **YOLOv8** and unzip it over a new folder `datasets/driver_state_checked`.

> **Important:** If Roboflow's free plan refuses that many images, upload **only the test split** (`images/test` + `labels/test`), check it, export, and copy the exported `test/images` and `test/labels` back into `datasets/driver_state/images/test` and `labels/test`. The class order is alphabetical in both (eye_closed, eye_open, phone, yawn), so the ids still match.

From here on, `DATA` means your checked dataset's `data.yaml`, e.g. `datasets/driver_state_checked/data.yaml` (or `datasets/driver_state/data.yaml`).

## Part E. Train YOLOv8, YOLO11 and YOLO26 (Google Colab)

All three are trained on the **same data, same image size, same epochs, same seed**, so the comparison is fair. Do not pick a winner before the numbers are in.

**Step 1.** Zip your checked dataset folder (e.g. `driver_state_checked`) and upload it to **Google Drive** → My Drive → a folder `thesis`.

**Step 2.** Open **colab.research.google.com** → New notebook → **Runtime → Change runtime type → T4 GPU**.

**Step 3.** Paste this into the first cell and run it:

```
from google.colab import drive; drive.mount('/content/drive')
!git clone -b claude/bold-volta-j8i7v3 https://github.com/idkshan/thesis-prototype.git
%cd thesis-prototype
!pip install -q -r requirements.txt
!python scripts/download_models.py
!mkdir -p datasets && unzip -q /content/drive/MyDrive/thesis/driver_state_checked.zip -d datasets/
!ls datasets
```

**Step 4.** Fix the dataset location inside `data.yaml` (Colab has different folders), then train. Replace `driver_state_checked` if your folder has another name:

```
!sed -i "s#^path:.*#path: /content/thesis-prototype/datasets/driver_state_checked#" datasets/driver_state_checked/data.yaml
!python scripts/train_yolo.py --data datasets/driver_state_checked/data.yaml --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt --epochs 100 --imgsz 640 --batch 16 --device 0
```

> **Tip:** Roboflow exports have no `path:` line, just `train: ../train/images` etc. In that case skip the `sed` line, or open `data.yaml` in Colab's file panel and set `train:`, `val:` and `test:` to the full paths.

**Step 5.** Export all three for Android while you still have Colab (TensorFlow is already installed there):

```
!python scripts/export_models.py --models runs/detect/yolov8n/weights/best.pt runs/detect/yolo11n/weights/best.pt runs/detect/yolo26n/weights/best.pt --imgsz 320 640 --formats tflite tflite_fp16 tflite_int8 --data datasets/driver_state_checked/data.yaml
!cp -r runs exports /content/drive/MyDrive/thesis/
```

**Step 6.** Download `runs` and `exports` from Google Drive and put both folders inside your local `thesis-prototype` folder.

> **You should see:** `runs/detect/yolov8n/weights/best.pt`, `runs/detect/yolo11n/weights/best.pt`, `runs/detect/yolo26n/weights/best.pt`, `runs/detect/training_summary.json`, and files like `exports/android/yolo11n_320_tflite_fp16.tflite`.

> **Important:** Colab disconnects after some idle time. Keep the tab open. If it stops mid-way, train one model per run (`--models models/yolo11n.pt`) and copy `runs` to Drive after each.

## Part F. Compare on the Desktop (Accuracy)

Accuracy is measured on the **test split** of the approved datasets, per class, at the confidence threshold the program uses (0.35):

```
python scripts/benchmark_yolo.py --models runs/detect/yolov8n/weights/best.pt runs/detect/yolo11n/weights/best.pt runs/detect/yolo26n/weights/best.pt --data datasets/driver_state_checked/data.yaml --split test --imgsz 320 640 --formats pytorch onnx
```

Results: `results/yolo_benchmark/results.md` (also `.csv`, `.json`).

| Column | Meaning |
|---|---|
| `precision`, `recall`, `f1`, `ap50` | Average over the four classes |
| `eye_closed_f1`, `eye_open_f1`, `yawn_f1`, `phone_f1` | F1 for each class (put these in your thesis table) |
| `size_mb`, `params_m`, `gflops` | Model size and compute (smaller = easier for the phone) |
| `total_ms_median`, `fps` | Desktop speed incl. post-processing (only a hint; the phone numbers in Part G count) |

## Part G. Compare on the Android Phone

This measures each exported model **on the real phone** that will run the app. It uses Google's official TFLite benchmark tool, so all three models are measured the same way, on CPU, GPU and NNAPI (the phone's AI chip).

### G.1 One-time phone setup

**Step 1.** On the phone: **Settings → About phone** → tap **Build number** 7 times ("You are now a developer").

**Step 2.** **Settings → System → Developer options** → turn on **USB debugging**.

**Step 3.** On the laptop: download **SDK Platform-Tools for Windows** (developer.android.com/tools/releases/platform-tools), unzip it to `C:\platform-tools`, and add that folder to PATH (Start → "Edit the system environment variables" → Environment Variables → Path → New).

**Step 4.** Open a **new** terminal, connect the phone by USB, accept the "Allow USB debugging?" prompt on the phone, and check:

```
adb devices
```

> **You should see:** A line with your phone's serial number followed by `device` (not `unauthorized`).

### G.2 Run the benchmark

**Step 1.** Charge the phone above 50 %, close other apps, keep the screen on, and let it cool down to room temperature.

**Step 2.** Run (it downloads the benchmark tool the first time):

```
python scripts/android_benchmark.py --models exports/android --delegates cpu gpu nnapi --repeats 3 --merge results/yolo_benchmark/results.csv
```

> **You should see:** For every model, size and format: the phone's inference time in ms, network FPS, start-up time, peak memory and battery temperature. Saved to `results/android_benchmark/results.md`, `results.csv` and `device.json` (phone model, chipset, Android version).

| Column | Meaning |
|---|---|
| `format` | `tflite` = FP32, `tflite_fp16` = half precision, `tflite_int8` = quantised (smallest, fastest on many phones, may lose accuracy) |
| `delegate` | `cpu` (4 threads), `gpu` (phone GPU), `nnapi` (phone AI chip). `failed` = not supported on that phone |
| `inference_ms_median`, `fps_network` | Speed of the network on the phone (median of 3 runs of 100 inferences) |
| `peak_mem_mb`, `battery_temp_after_c` | Memory use and heat |
| `desktop_f1`, `desktop_ap50` | Accuracy from Part F, copied in for one combined table |

> **Important:** YOLOv8 and YOLO11 still need an NMS clean-up step after the network; YOLO26 does not (it is NMS-free). The phone tool measures the network only, so also note the desktop `post_ms` column, or better, measure the frame rate **inside the Android app** with the winning model (ask Sir Melvin about adding a simple FPS counter to the app).

### G.3 Choose the winner (rule fixed BEFORE testing)

**Step 1.** Keep only models whose average **F1 ≥ 0.80** (and eye_closed / yawn / phone F1 each ≥ 0.80) on the test split, and that reach **≥ 10 FPS** on the phone.

**Step 2.** Of those, pick the highest F1.

**Step 3.** If two are within 0.02 F1, pick the faster one on the phone; if the speed is also within 10 %, pick the smaller file.

**Step 4.** Report the full table for all three anyway: the comparison itself is a result.

## Part H. Use the Winner in the Program

```
python scripts/run_monitor.py --source 0 --yolo runs/detect/yolo11n/weights/best.pt
```

(Replace `yolo11n` with your winner. For the ESP32-CAM use `--source http://<esp32-ip>:81/stream`.)

> **You should see:** Green/red boxes labelled `eye_open` / `eye_closed`, a `yawn` box when you yawn, a `phone` box, and the fatigue bar showing **Alert / Level 1 / Level 2 / Level 3**.

What happens inside, using a yawn as the example:

| Stage | Example |
|---|---|
| AI identifies | YOLO draws a `yawn` box over the mouth; MediaPipe's jawOpen is high; MAR is high. |
| Evidence | The three sources are averaged into mouth evidence 0-1 (0.5 = your personal threshold). |
| Threshold | Mouth wide open for ≥ 1.5 s → one yawn (shorter = speech). Eyes are not judged while yawning (squinting is not drowsiness). |
| Severity | 1-2 yawns in 5 min → MODERATE; 3+ → SEVERE. |
| Combined | Yawning + eye closures together → one drowsiness level higher → stronger alarm. |

### H.1 Speed options for the laptop / phone

| Option | Effect |
|---|---|
| `--imgsz 320` | Smaller input: about 3x faster, slightly less accurate for small objects (eyes) |
| `--yolo-every 2` | Run YOLO every 2nd frame and reuse the boxes in between |
| `--conf 0.4` | Fewer, more certain boxes |
| `--eye-mode ear --mouth-mode mar` | Thresholds only, no AI evidence (for the ablation, Part I.6) |

## Part I. The 30-Participant Study (Validation and Test)

### I.1 Split the participants BEFORE looking at results

Assign every participant to **one** split and never move them:

| Split | Participants | Used for |
|---|---|---|
| **Validation** | 10 (e.g. P01-P10) | Tuning: you may change thresholds and durations until these results are good. |
| **Test** | 20 (e.g. P11-P30) | The numbers in your thesis. Run once, with the final settings. No tuning after looking at them. |

> **Tip:** Mix the splits fairly: put men and women, glasses and no glasses, and day and night sessions in both. 15 / 15 is also acceptable; 10 / 20 gives more trustworthy test numbers.

> **Important:** The program refuses a manifest where the same participant is in both splits (that would be data leakage).

### I.2 Record each session (30-45 minutes)

**Step 1.** Get written consent (Data Privacy Act). Name each file by participant and condition: `P01_day.mp4`, `P01_night.mp4`.

**Step 2.** Mount the ESP32-CAM where it will be in the car, **within about 20° of straight in front of the driver's face** (a camera far to the side makes eyes look closed and head turns look smaller).

**Step 3.** Record plain video (not the program window). Start with 10 seconds of the driver looking ahead with a relaxed face (calibration).

**Step 4.** Follow a script so every behaviour occurs several times: yawns (spaced out), eye closures of about 1, 2 and 3 s, mirror glances (~1 s, should NOT alarm), looking away / down 3-5 s, phone use 5-10 s, and long periods of normal driving (needed for the false-alarm rate). Natural drowsiness late in a long session is even better.

**Step 5.** Put all videos in `data/study/videos`.

### I.3 Label each video

Make one CSV per video in `data/study/labels` (Excel → Save As → **CSV UTF-8** is fine). Times are seconds from the start of the video. A template is in `docs/templates/labels_template.csv`:

```
behavior,start_s,end_s
yawning,63.2,68.9
gaze_deviation,120.0,124.5
phone_usage,300.1,309.8
slightly_drowsy,900.0,960.0
moderately_drowsy,1500.0,1560.0
severely_drowsy,2100.0,2130.0
```

| Name | Meaning |
|---|---|
| `yawning` | One row per yawn (start = mouth opens, end = mouth closes) |
| `gaze_deviation` | Head/eyes clearly off the road ≥ 2 s. Do not label quick mirror checks. |
| `phone_usage` | Phone in hand and being used |
| `slightly_drowsy` / `moderately_drowsy` / `severely_drowsy` | A drowsy period with its level (Level 1 / 2 / 3, see Part J). One row for the whole period, not per blink. Plain `drowsiness` also works if you do not grade the level. |

> **Tip:** Two people labelling the same few videos independently and comparing (inter-rater agreement) makes the labels defensible in the defence.

### I.4 Make the manifest

One row per video, in `data/study/manifest.csv` (template: `docs/templates/study_manifest_template.csv`). Label paths are relative to the manifest's folder:

```
participant,session,labels,split,condition
P01,P01_day,labels/P01_day.csv,validation,day
P01,P01_night,labels/P01_night.csv,validation,night
P11,P11_day,labels/P11_day.csv,test,day
```

### I.5 Run the program on every video

```
python scripts/process_videos.py --videos data/study/videos --yolo runs/detect/yolo11n/weights/best.pt
```

> **You should see:** One folder per video in `results/study_sessions/` (e.g. `P01_day/frames.jsonl`). Already finished videos are skipped, so you can stop and restart.

> **Tip:** This runs the AI once. Everything after this (tuning, ablation, test) re-uses the saved results and takes seconds, because of `--replay`.

### I.6 Validate and tune (validation split only)

**Step 1.** Score the validation participants with the default settings:

```
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split validation --tag val_default
```

Open `results/study_eval/val_default/summary.md`. It shows, per behaviour: labelled events, detected, false detections, event precision / recall / F1 against the targets, frame F1, F1 per participant (mean ± SD), median detection delay, unnecessary alarms per hour (target ≤ 0.5/h), and the drowsiness-level confusion matrix, for all sessions and separately for day and night. `per_session.csv` shows which participant/video causes problems.

**Step 2.** If a behaviour is missed or over-detected, copy `configs/default.yaml` to `configs/tuned.yaml` and change the relevant value (every value has a comment). Examples: `gaze_min_s`, `yawn_min_s`, `phone_confirm_s`, `ear_closed_ratio`, the `severity` tables.

**Step 3.** Re-score instantly with the new settings:

```
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split validation --config configs/tuned.yaml --tag val_tuned
```

**Step 4.** Repeat until the validation results meet the targets (or stop improving). Then **freeze** `configs/tuned.yaml`. Write down what you changed and why (for Chapter 4).

### I.7 Test (once) and the ablation

**Step 1.** Final results on the test participants with the frozen settings:

```
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --config configs/tuned.yaml --tag final
```

**Step 2.** Show what the AI adds, on the same test participants (thresholds only / AI only / combined):

```
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --config configs/tuned.yaml --eye-mode ear --mouth-mode mar --tag test_thresholds_only
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --config configs/tuned.yaml --eye-mode yolo --mouth-mode yolo --tag test_yolo_only
python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --config configs/tuned.yaml --tag test_combined
```

| Run | Eye / mouth evidence used | Shows |
|---|---|---|
| thresholds_only | EAR / MAR thresholds | The old, rule-only approach |
| yolo_only | YOLO eye_closed / yawn boxes | The AI alone |
| combined (default) | EAR/MAR + MediaPipe blendshapes + YOLO | AI + thresholds integrated (your system) |

Put the drowsiness and yawning event F1 of the three runs side by side in one table. Other modes: `--eye-mode blendshape|fused|ear+yolo|cnn`, `--mouth-mode blendshape|fused|mar+yolo`.

> **Important:** Do NOT tune on the test split. If you change settings after seeing test results, the test numbers are no longer an honest estimate. Re-running the same frozen settings is fine.

### I.8 Which YOLO model in the study?

Use the winner from Part G for the main results. If you also want the three models compared on the participants (strong evidence for the Android choice), process the videos once per model into separate folders and evaluate each:

```
python scripts/process_videos.py --videos data/study/videos --yolo runs/detect/yolov8n/weights/best.pt --log-dir results/study_v8
python scripts/evaluate_study.py --manifest data/study/manifest.csv --sessions results/study_v8 --split test --config configs/tuned.yaml --tag test_yolov8n
```

(Repeat with `yolo11n` and `yolo26n`.)

## Part J. Criteria Table (Mild / Moderate / Severe)

Your adviser asked for explicit criteria. These are the program's current values (`configs/default.yaml`); if you tune them in Part I.6, update the table. Full version: `docs/Severity_Criteria_Table.md`.

### J.1 Severity per behaviour

| Behaviour | Measure | MILD | MODERATE | SEVERE |
|---|---|---|---|---|
| Drowsiness (eyes) | Longest eye closure | 0.5-0.8 s | 0.8-1.5 s | ≥ 1.5 s |
|  | or PERCLOS (60 s) | 5-8 % | 8-15 % | ≥ 15 % |
| Yawning | Yawns in 5 min | - | 1-2 | ≥ 3 |
| Gaze deviation | Looking away | 1.3-2 s | 2-3 s | ≥ 3 s |
|  | or eyes off road (30 s) | 15-25 % | 25-40 % | ≥ 40 % |
| Phone use | Phone in view | 1.7-3 s | - | ≥ 3 s |

### J.2 Drowsiness levels (drowsy + yawning combined)

| Level | Criteria | Alarm |
|---|---|---|
| Alert | Eye closure and yawning at most MILD | None |
| Level 1 - slightly drowsy | Eye closure OR yawning MODERATE (one of them) | LOW (soft buzzer) |
| Level 2 - moderately drowsy | Eye closure OR yawning SEVERE, or BOTH MODERATE (drowsy + yawning) | MEDIUM (repeating buzzer) |
| Level 3 - severely drowsy | One SEVERE AND the other at least MODERATE (drowsy + yawning) | HIGH (alarm + push notification) |

After about 2 hours of continuous driving, Level 1 and above rise one more level (driving time alone never raises it). Distraction: gaze or phone MODERATE → MEDIUM; SEVERE → HIGH. Stopped / very slow and smooth → one tier lower; erratic motion → HIGH.

> **Tip:** Your labelled levels (`slightly_drowsy` etc.) are checked against these levels by `evaluate_study.py` (confusion matrix in `summary.md`).

## Troubleshooting

| Problem | Fix |
|---|---|
| `No module named ...` | The virtual environment is not active: `.venv\Scripts\Activate.ps1`. |
| `FileNotFoundError` for a labels / manifest file | Check the path and spelling. Run commands from the `thesis-prototype` folder. In PowerShell, `ls data/study/labels` shows what is there. |
| Manifest error: missing column | The first line must be exactly `participant,session,labels,split,condition`. |
| `Missing files ... frames.jsonl` | That video was not processed yet (I.5), or the session name in the manifest differs from the video file name. |
| Eye boxes never appear | Check that the model was trained on the driver-state dataset (`eye_open`/`eye_closed` in its class names). The COCO models (`models/yolo11n.pt`) only know phones. |
| Drowsiness detected all the time | Camera too far to the side (keep within 20°), or calibration happened while the driver was not looking ahead. Press `c` to recalibrate in live mode. |
| `adb` is not recognized | Platform-tools folder not on PATH, or the terminal was opened before you changed PATH. Open a new terminal. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB-debugging prompt; re-plug the cable. |
| Android benchmark: `failed (delegate not supported?)` | That phone has no GPU/NNAPI support for this model. Report it as "not supported"; CPU results are still valid. |
| TFLite export fails on Windows | Do the export on Colab (Part E step 6), where TensorFlow is pre-installed. |
| INT8 accuracy much lower than FP16 | Normal for small objects such as eyes. Report it, and prefer FP16 if INT8 falls below the F1 target. |

## Checklist and Command Summary

- [ ] A: new code downloaded, old folders moved in, `72 passed`

- [ ] B: YawDD, State Farm, SynDD1 (and AUC) in `data/`

- [ ] C: phone dataset checked in Roboflow, incl. SynDD1 dashboard frames → `datasets/phone_roboflow`

- [ ] D: `datasets/driver_state` built; at least the test split checked

- [ ] E: three models trained with identical settings; TFLite files exported

- [ ] F: desktop comparison table (per-class F1)

- [ ] G: Android comparison table (phone model and chipset recorded)

- [ ] H: winner chosen with the pre-set rule and used in the program

- [ ] I: 30 participants recorded, labelled, split 10 validation / 20 test; tuned on validation; final test run; ablation

- [ ] J: criteria table in the thesis matches `configs/tuned.yaml`

### Commands

| Task | Command |
|---|---|
| Tests | `python -m pytest` |
| Dashboard frames | `python scripts/extract_frames.py --videos data/syndd1/Dashboard --out data/syndd1_frames --every-sec 2` |
| Driver-state dataset | `python scripts/build_driver_state_dataset.py --yawdd data/yawdd --phone-dataset datasets/phone_roboflow --out datasets/driver_state` |
| Train | `python scripts/train_yolo.py --data DATA --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt --epochs 100 --device 0` |
| Desktop compare | `python scripts/benchmark_yolo.py --models <3 best.pt> --data DATA --split test --imgsz 320 640` |
| Export for Android | `python scripts/export_models.py --models <3 best.pt> --imgsz 320 640 --data DATA` |
| Phone compare | `python scripts/android_benchmark.py --models exports/android --delegates cpu gpu nnapi --merge results/yolo_benchmark/results.csv` |
| Live | `python scripts/run_monitor.py --source 0 --yolo <winner best.pt>` |
| Process study videos | `python scripts/process_videos.py --videos data/study/videos --yolo <winner best.pt>` |
| Validation | `python scripts/evaluate_study.py --manifest data/study/manifest.csv --split validation --config configs/tuned.yaml --tag val` |
| Test | `python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --config configs/tuned.yaml --tag final` |
| One session | `python scripts/evaluate_behaviors.py --session results/study_sessions/P01_day --labels data/study/labels/P01_day.csv` |
| Rule tables | `python scripts/export_rule_tables.py` |

More detail: `README.md`, `docs/GUIDE_v2.md` (this guide), `docs/Severity_Criteria_Table.md`, `docs/Appendix_Rule_Tables.md`.
