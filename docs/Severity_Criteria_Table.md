# Severity Criteria: Mild / Moderate / Severe and Drowsiness Levels 1–3

This table answers the adviser's request for explicit criteria. **The AI identifies the behaviour; the thresholds set
its severity.** Every number below comes from `configs/default.yaml` (Stage 3 persistence rules and Stage 4 severity
anchors) and from the Stage 5 fuzzy rule base (`dms/risk/rules.py`). To change a criterion, edit the config and
re-run `python scripts/export_rule_tables.py`. Tune the values on the **validation** participants only.

## 1. Who decides what

| Step | Done by | Output |
|---|---|---|
| Is the eye closed? Is the driver yawning? Is a phone in hand? Where is the head pointing? | **AI**: MediaPipe Face Landmarker (landmarks, eyeBlink/jawOpen blendshapes), YOLO driver-state detector (eye_open, eye_closed, yawn, phone), optional eye-state CNN | Per-frame evidence (0–1) |
| Is it a real behaviour or just a blink, speech, or a mirror check? | **Thresholds**: personal calibration + minimum durations | Behaviour events (closure, yawn, gaze away, phone use) |
| How serious is it? | **Thresholds**: severity anchors (table below) | NONE / MILD / MODERATE / SEVERE per behaviour |
| How drowsy is the driver overall? How risky is the situation? | **Fuzzy rules** (Mamdani, 81 rules) | Drowsiness level (Alert, Level 1–3), alarm tier |

## 2. Detection thresholds (when a behaviour is detected at all)

| Behaviour | AI evidence | Detected when |
|---|---|---|
| Eye closure | EAR < 0.70 × the driver's own baseline, eyeBlink > baseline + 0.35, YOLO `eye_closed` box (fused, ≥ 0.5) | Closed ≥ 0.5 s (shorter = blink). Not judged while a phone is visible or while yawning (+0.5 s). |
| Yawning | MAR > baseline + 0.40, jawOpen > baseline + 0.35, YOLO `yawn` box (fused, ≥ 0.5) | Mouth wide open ≥ 1.5 s (shorter = speech) |
| Gaze deviation | Head yaw > 30° or pitch < −20° (down) / > +25° (up) from the driver's forward pose | Away ≥ 2.0 s (shorter = mirror check) |
| Phone use | YOLO `phone` (or COCO `cell phone`) box near the driver's face | Visible ≥ 3.0 s |

## 3. Severity criteria per behaviour (Stage 4)

| Behaviour | Measure | MILD | MODERATE | SEVERE |
|---|---|---|---|---|
| Drowsiness (eyes) | Longest current eye closure | 0.5 – 0.8 s | 0.8 – 1.5 s | ≥ 1.5 s (microsleep) |
| | **or** PERCLOS (% of last 60 s with eyes closed) | 5 – 8 % | 8 – 15 % | ≥ 15 % |
| Yawning | Yawns in the last 5 min | — | 1 – 2 yawns | ≥ 3 yawns |
| Gaze deviation | Continuous time looking away | 1.3 – 2 s | 2 – 3 s | ≥ 3 s |
| | **or** eyes-off-road share of the last 30 s | 15 – 25 % | 25 – 40 % | ≥ 40 % |
| Phone use | Continuous time with a phone in view | 1.7 – 3 s (not yet confirmed) | — | ≥ 3 s (confirmed use) |

The stronger of the two measures counts (e.g. a 1.6 s closure is SEVERE even if PERCLOS is low).
Cut-offs: MILD ≥ 0.10, MODERATE ≥ 1/3, SEVERE ≥ 0.65 on the 0–1 severity score.

## 4. Drowsiness levels (fatigue, Stage 5) — combining detections

| Level | Criteria (normal drive, under 2 h) | Example | Alarm tier |
|---|---|---|---|
| **Alert** | Eye closure and yawning both at most MILD | normal blinking, talking | NONE |
| **Level 1 – slightly drowsy** | Eye closure **or** yawning MODERATE (only one of them) | one yawn; closures of about 1 s | LOW (soft buzzer) |
| **Level 2 – moderately drowsy** | Eye closure **or** yawning SEVERE, **or both** MODERATE (**drowsy + yawning together**) | 1.5 s microsleep; 3 yawns in 5 min; 1 s closures and a yawn | MEDIUM (repeating buzzer) |
| **Level 3 – severely drowsy** | One SEVERE **and** the other at least MODERATE (**drowsy + yawning together**) | microsleep and a recent yawn | HIGH (repeated alarm + push notification) |

**Drowsy + yawning = higher risk.** When both cues are present, the level rises by one: two moderate cues give
Level 2 instead of Level 1, and one severe plus one moderate cue gives Level 3 instead of Level 2. After about
**2 hours of continuous driving**, any level from Level 1 upward rises one more level (capped at Level 3).
Driving time alone never raises the level.

Verified outcomes (tests/test_fuzzy.py), 40 km/h, smooth road:

| Eye-closure severity | Yawning severity | Level | Tier |
|---|---|---|---|
| none | MODERATE (1 yawn) | Level 1 | LOW |
| SEVERE (≥ 1.5 s) | none | Level 2 | MEDIUM |
| MODERATE | MODERATE | Level 2 | MEDIUM |
| SEVERE | MODERATE | Level 3 | HIGH |
| MODERATE, after 2.5 h of driving | none | Level 2 | MEDIUM |

## 5. Distraction and context

| Situation | Alarm tier |
|---|---|
| Gaze or phone MODERATE | MEDIUM |
| Gaze or phone SEVERE, or both MODERATE | HIGH |
| Fatigue Level 1 + distraction MODERATE | MEDIUM; Level 2 + MODERATE distraction → HIGH |
| Vehicle stopped / very slow and smooth | one tier lower |
| Erratic motion (MPU6050 ≥ about 0.45 g) | HIGH |

## 6. How the criteria are validated with the participants

Annotate drowsiness segments in the label CSV with their level (`slightly_drowsy`, `moderately_drowsy`,
`severely_drowsy`). `scripts/evaluate_study.py` compares each annotated level with the highest level the system
reported inside that segment and prints a confusion matrix (exact agreement and agreement within one level),
separately for the validation and test participants and for day and night sessions.
