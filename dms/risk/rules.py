"""Stage 5: fuzzy sets and the four rule blocks of Table 1.6.

Hierarchical design: seven indicators feed three small blocks (Fatigue,
Distraction, Context) whose outputs feed an Overall-Risk block. This needs
27 + 9 + 9 + 27 = 72 rules instead of 3^7 = 2,187 for a flat rule base.

Each rule base is generated from a short, stated principle so that it is
complete (every combination has exactly one rule) and easy to audit. Use
``scripts/export_rule_tables.py`` to print the full tables for the thesis.
"""

from __future__ import annotations

from itertools import product
from typing import Dict, List

from .fuzzy import FuzzySet, FuzzyVariable, Rule

LEVELS = ("Low", "Moderate", "High")
RISK_LEVELS = ("NONE", "LOW", "MEDIUM", "HIGH")  # = alarm tiers of SO5

# Table 1.7: Low peaks at 0 and fades by 0.4; Moderate rises from 0.3, peaks
# at 0.5, fades by 0.7; High rises from 0.6 and is fully High from 0.8.
UNIT_SETS = {
    "Low": [(0.0, 1.0), (0.4, 0.0)],
    "Moderate": [(0.3, 0.0), (0.5, 1.0), (0.7, 0.0)],
    "High": [(0.6, 0.0), (0.8, 1.0)],
}

# Overall risk R on 0..100. Set names match the alarm tiers of SO5:
# NONE = no alarm, LOW = soft buzzer, MEDIUM = repeating buzzer,
# HIGH = repeated alarm + push notification.
RISK_SETS = {
    "NONE": [(0.0, 1.0), (30.0, 0.0)],
    "LOW": [(20.0, 0.0), (40.0, 1.0), (60.0, 0.0)],
    "MEDIUM": [(45.0, 0.0), (65.0, 1.0), (85.0, 0.0)],
    "HIGH": [(70.0, 0.0), (90.0, 1.0)],
}


def unit_variable(name: str) -> FuzzyVariable:
    return FuzzyVariable(name, 0.0, 1.0, {k: FuzzySet(k, v) for k, v in UNIT_SETS.items()})


def risk_variable(name: str = "risk") -> FuzzyVariable:
    return FuzzyVariable(name, 0.0, 100.0, {k: FuzzySet(k, v) for k, v in RISK_SETS.items()})


def fatigue_rules() -> List[Rule]:
    """F = stronger of the two visual fatigue cues (eye closure, yawning).
    A long driving duration escalates Moderate fatigue to High; duration
    alone never raises fatigue."""
    rules = []
    for e, y, d in product(range(3), repeat=3):
        level = max(e, y)
        if level == 1 and d == 2:
            level = 2
        rules.append(Rule({"eye_closure": LEVELS[e], "yawning": LEVELS[y], "duration": LEVELS[d]}, LEVELS[level]))
    return rules


def distraction_rules() -> List[Rule]:
    """D = stronger of gaze deviation and phone use; both Moderate together -> High."""
    rules = []
    for g, p in product(range(3), repeat=2):
        level = max(g, p)
        if g == 1 and p == 1:
            level = 2
        rules.append(Rule({"gaze": LEVELS[g], "phone": LEVELS[p]}, LEVELS[level]))
    return rules


def context_rules() -> List[Rule]:
    """C = erratic motion dominates; speed alone can raise C to Moderate at most
    (an alert driver at speed is not an alarm); Moderate motion at High speed -> High."""
    rules = []
    for s, m in product(range(3), repeat=2):
        level = max(m, min(s, 1))
        if m == 1 and s == 2:
            level = 2
        rules.append(Rule({"speed": LEVELS[s], "motion": LEVELS[m]}, LEVELS[level]))
    return rules


# Driver-state risk for normal driving context, indexed [fatigue][distraction].
# Encodes Section 1.7.1: one yawn -> LOW; frequent yawning or drowsiness ->
# MEDIUM; distraction -> HIGH. Fatigue and distraction together compound.
OVERALL_BASE = [
    ["NONE", "MEDIUM", "HIGH"],  # fatigue Low
    ["LOW", "MEDIUM", "HIGH"],  # fatigue Moderate
    ["MEDIUM", "HIGH", "HIGH"],  # fatigue High
]


def overall_rules() -> List[Rule]:
    """Context modulates the driver-state risk:
    Low context (stopped / very slow, smooth) -> one tier lower;
    Moderate context (normal driving)         -> unchanged;
    High context (erratic / sudden motion)    -> HIGH (Section 1.7.1)."""
    rules = []
    for f, d, c in product(range(3), repeat=3):
        base = RISK_LEVELS.index(OVERALL_BASE[f][d])
        if c == 2:
            out = 3
        elif c == 0:
            out = max(0, base - 1)
        else:
            out = base
        rules.append(Rule({"fatigue": LEVELS[f], "distraction": LEVELS[d], "context": LEVELS[c]}, RISK_LEVELS[out]))
    return rules


def rule_table_markdown(rules: List[Rule], output: str) -> str:
    """Render a rule base as a Markdown table."""
    cols = list(rules[0].antecedent)
    lines = ["| # | " + " | ".join(cols) + f" | {output} |", "|---|" + "---|" * (len(cols) + 1)]
    for i, r in enumerate(rules, 1):
        lines.append(f"| {i} | " + " | ".join(r.antecedent[c] for c in cols) + f" | {r.consequent} |")
    return "\n".join(lines)


def all_rule_blocks() -> Dict[str, List[Rule]]:
    return {
        "fatigue": fatigue_rules(),
        "distraction": distraction_rules(),
        "context": context_rules(),
        "overall": overall_rules(),
    }
