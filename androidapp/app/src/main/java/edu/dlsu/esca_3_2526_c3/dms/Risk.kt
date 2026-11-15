package edu.dlsu.esca_3_2526_c3.dms

/*
 * Stage 4 (severity) and Stage 5 (hierarchical Mamdani fuzzy risk).
 * Kotlin port of dms/severity.py, dms/risk/fuzzy.py, rules.py, driver_risk.py.
 */

// ------------------------------------------------------------------------- Stage 4
object SeverityLevels {
    const val MILD_AT = 0.10
    const val MODERATE_AT = 1.0 / 3.0
    const val SEVERE_AT = 0.65
    val EYE_LEVEL_NAMES = listOf("alert", "slightly drowsy", "moderately drowsy")

    fun levelFor(score: Double): String = when {
        score < MILD_AT -> "NONE"
        score < MODERATE_AT -> "MILD"
        score < SEVERE_AT -> "MODERATE"
        else -> "SEVERE"
    }
}

class SeverityGrader(private val cfg: SeverityConfig, stateScores: List<Double>? = null) {
    private val stateScores = stateScores ?: listOf(0.0, 0.45, 0.75)

    private fun sev(score: Double, reason: String) = Severity(Num.pyRound(score, 4), SeverityLevels.levelFor(score), reason)

    fun grade(b: BehaviorStatus): SeverityReport {
        val c = cfg
        val closure = Num.piecewise(c.closureS, b.closureS)
        val eyeState = stateScores[b.eyeLevel.coerceIn(0, stateScores.size - 1)]
        val drowsy = if (closure >= eyeState) sev(closure, "eye closure %.1fs".format(b.closureS))
        else {
            val pc = b.perclos?.let { ", PERCLOS %.0f%%".format(100 * it) } ?: ""
            sev(eyeState, "eyes ${SeverityLevels.EYE_LEVEL_NAMES[b.eyeLevel]} (${b.longClosures} long closures$pc)")
        }
        val yawn = sev(Num.piecewise(c.yawnCount, b.yawnsInWindow.toDouble()), "${b.yawnsInWindow} yawn(s) in window")
        val away = Num.piecewise(c.gazeS, b.gazeAwayS)
        val offroad = Num.piecewise(c.offroadRatio, b.offroadRatio)
        val gaze = if (away >= offroad) sev(away, "looking away %.1fs".format(b.gazeAwayS))
        else sev(offroad, "eyes off road %.0f%% of window".format(100 * b.offroadRatio))
        val phone = sev(Num.piecewise(c.phoneS, b.phoneS), "phone visible %.1fs".format(b.phoneS))
        return SeverityReport(drowsy, yawn, gaze, phone)
    }
}

// ------------------------------------------------------------------------- fuzzy engine
class FuzzySet(val name: String, points: List<Pair<Double, Double>>) {
    private val xs = points.map { it.first }.toDoubleArray()
    private val ys = points.map { it.second }.toDoubleArray()
    fun mu(x: Double) = Num.interp(x, xs, ys)
}

class FuzzyVariable(val name: String, val lo: Double, val hi: Double, val sets: LinkedHashMap<String, FuzzySet>) {
    fun fuzzify(x: Double): Map<String, Double> {
        val v = minOf(hi, maxOf(lo, x))
        return sets.mapValues { it.value.mu(v) }
    }
}

class Rule(val antecedent: LinkedHashMap<String, String>, val consequent: String) {
    fun text(output: String) = "IF " + antecedent.entries.joinToString(" AND ") { "${it.key} is ${it.value}" } + " THEN $output is $consequent"
}

class InferenceResult(val value: Double, val firing: List<Pair<Rule, Double>>)

/** Mamdani inference: min for AND, max aggregation, centroid defuzzification on `resolution` samples. */
class MamdaniSystem(val name: String, inputs: List<FuzzyVariable>, private val output: FuzzyVariable, private val rules: List<Rule>, resolution: Int = 201) {
    private val inputs = inputs.associateBy { it.name }
    private val universe = DoubleArray(resolution) { i ->
        // numpy.linspace: start + i * step, the last sample exactly at the end
        if (i == resolution - 1) output.hi else output.lo + i * ((output.hi - output.lo) / (resolution - 1))
    }
    private val outMu = output.sets.mapValues { (_, s) -> DoubleArray(resolution) { s.mu(universe[it]) } }

    init {
        for (r in rules) require(r.antecedent.keys.all { it in this.inputs } && r.consequent in output.sets) { "Rule references unknown variable/set" }
    }

    fun infer(values: Map<String, Double>): InferenceResult {
        val memberships = inputs.mapValues { (n, v) -> v.fuzzify(values.getValue(n)) }
        val aggregated = DoubleArray(universe.size)
        val firing = ArrayList<Pair<Rule, Double>>(rules.size)
        for (rule in rules) {
            val alpha = rule.antecedent.entries.minOf { (v, s) -> memberships.getValue(v).getValue(s) }
            firing.add(rule to alpha)
            if (alpha > 0) {
                val mu = outMu.getValue(rule.consequent)
                for (i in aggregated.indices) aggregated[i] = maxOf(aggregated[i], minOf(alpha, mu[i]))
            }
        }
        val area = pairwiseSum(aggregated.size) { aggregated[it] }
        val value = if (area <= 1e-12) output.lo else pairwiseSum(aggregated.size) { i -> universe[i] * aggregated[i] } / area
        return InferenceResult(value, firing)
    }

    // numpy's sum uses pairwise summation; doing the same keeps the centroid identical to the desktop
    private fun pairwiseSum(size: Int, term: (Int) -> Double): Double = pairwise(0, size, term)

    private fun pairwise(lo: Int, n: Int, term: (Int) -> Double): Double {
        if (n < 8) {
            var s = 0.0
            for (i in lo until lo + n) s += term(i)
            return s
        }
        if (n <= 128) {
            val r = DoubleArray(8) { term(lo + it) }
            var i = 8
            while (i < n - n % 8) {
                for (k in 0..7) r[k] += term(lo + i + k)
                i += 8
            }
            var s = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) { s += term(lo + i); i++ }
            return s
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return pairwise(lo, n2, term) + pairwise(lo + n2, n - n2, term)
    }
}

object Rules {
    val LEVELS = listOf("Low", "Moderate", "High")
    val FATIGUE_LEVELS = listOf("Low", "Moderate", "High", "Severe")
    val RISK_LEVELS = RISK_TIERS

    private fun sets(vararg defs: Pair<String, List<Pair<Double, Double>>>) = LinkedHashMap<String, FuzzySet>().apply {
        for ((n, pts) in defs) put(n, FuzzySet(n, pts))
    }

    // Table 1.7
    fun unitVariable(name: String) = FuzzyVariable(name, 0.0, 1.0, sets(
        "Low" to listOf(0.0 to 1.0, 0.4 to 0.0),
        "Moderate" to listOf(0.3 to 0.0, 0.5 to 1.0, 0.7 to 0.0),
        "High" to listOf(0.6 to 0.0, 0.8 to 1.0),
    ))

    fun fatigueVariable(name: String = "fatigue") = FuzzyVariable(name, 0.0, 1.0, sets(
        "Low" to listOf(0.0 to 1.0, 0.3 to 0.0),
        "Moderate" to listOf(0.2 to 0.0, 0.4 to 1.0, 0.6 to 0.0),
        "High" to listOf(0.45 to 0.0, 0.65 to 1.0, 0.85 to 0.0),
        "Severe" to listOf(0.7 to 0.0, 0.9 to 1.0),
    ))

    fun riskVariable(name: String = "risk") = FuzzyVariable(name, 0.0, 100.0, sets(
        "NONE" to listOf(0.0 to 1.0, 30.0 to 0.0),
        "LOW" to listOf(20.0 to 0.0, 40.0 to 1.0, 60.0 to 0.0),
        "MEDIUM" to listOf(45.0 to 0.0, 65.0 to 1.0, 85.0 to 0.0),
        "HIGH" to listOf(70.0 to 0.0, 90.0 to 1.0),
    ))

    private fun rule(vararg ante: Pair<String, String>, out: String) = Rule(linkedMapOf(*ante), out)

    /** F = stronger of eye closure and yawning; +1 when both >= Moderate; +1 after a long drive (if >= 1). 27 rules. */
    fun fatigueRules() = buildList {
        for (e in 0..2) for (y in 0..2) for (d in 0..2) {
            var level = maxOf(e, y)
            if (e >= 1 && y >= 1) level += 1
            if (level >= 1 && d == 2) level += 1
            level = minOf(level, 3)
            add(rule("eye_closure" to LEVELS[e], "yawning" to LEVELS[y], "duration" to LEVELS[d], out = FATIGUE_LEVELS[level]))
        }
    }

    /** D = stronger of gaze and phone; both Moderate -> High. 9 rules. */
    fun distractionRules() = buildList {
        for (g in 0..2) for (p in 0..2) {
            var level = maxOf(g, p)
            if (g == 1 && p == 1) level = 2
            add(rule("gaze" to LEVELS[g], "phone" to LEVELS[p], out = LEVELS[level]))
        }
    }

    /** C = erratic motion dominates; speed alone at most Moderate; Moderate motion at High speed -> High. 9 rules. */
    fun contextRules() = buildList {
        for (s in 0..2) for (m in 0..2) {
            var level = maxOf(m, minOf(s, 1))
            if (m == 1 && s == 2) level = 2
            add(rule("speed" to LEVELS[s], "motion" to LEVELS[m], out = LEVELS[level]))
        }
    }

    private val OVERALL_BASE = listOf(
        listOf("NONE", "MEDIUM", "HIGH"),
        listOf("LOW", "MEDIUM", "HIGH"),
        listOf("MEDIUM", "HIGH", "HIGH"),
        listOf("HIGH", "HIGH", "HIGH"),
    )

    /** Low context -> one tier lower; Moderate -> unchanged; High (erratic motion) -> HIGH. 36 rules. */
    fun overallRules() = buildList {
        for (f in 0..3) for (d in 0..2) for (c in 0..2) {
            val base = RISK_LEVELS.indexOf(OVERALL_BASE[f][d])
            val out = when (c) { 2 -> 3; 0 -> maxOf(0, base - 1); else -> base }
            add(rule("fatigue" to FATIGUE_LEVELS[f], "distraction" to LEVELS[d], "context" to LEVELS[c], out = RISK_LEVELS[out]))
        }
    }
}

fun tierFor(risk: Double, bounds: List<Double>): String = when {
    risk >= bounds[2] -> "HIGH"
    risk >= bounds[1] -> "MEDIUM"
    risk >= bounds[0] -> "LOW"
    else -> "NONE"
}

/** Risk tier with hysteresis: up at the bound, down only below bound - margin. */
class TierTracker(private val bounds: List<Double>, private val margin: Double) {
    var tier = "NONE"
        private set

    fun update(risk: Double): String {
        val raw = tierFor(risk, bounds)
        var cur = RISK_TIERS.indexOf(tier)
        if (RISK_TIERS.indexOf(raw) > cur) {
            tier = raw
        } else {
            while (cur > 0 && risk < bounds[cur - 1] - margin) cur -= 1
            tier = RISK_TIERS[cur]
        }
        return tier
    }
}

class DriverRiskModel(private val cfg: Config) {
    private val fatigue: MamdaniSystem
    private val distraction: MamdaniSystem
    private val context: MamdaniSystem
    private val overall: MamdaniSystem
    private val fatigueVar = Rules.fatigueVariable()
    private val tiers = TierTracker(cfg.risk.tierBounds, cfg.risk.tierHysteresis)

    init {
        val res = cfg.risk.resolution
        val u = Rules::unitVariable
        fatigue = MamdaniSystem("fatigue", listOf(u("eye_closure"), u("yawning"), u("duration")), Rules.fatigueVariable(), Rules.fatigueRules(), res)
        distraction = MamdaniSystem("distraction", listOf(u("gaze"), u("phone")), u("distraction"), Rules.distractionRules(), res)
        context = MamdaniSystem("context", listOf(u("speed"), u("motion")), u("context"), Rules.contextRules(), res)
        overall = MamdaniSystem("overall", listOf(Rules.fatigueVariable(), u("distraction"), u("context")), Rules.riskVariable("risk"), Rules.overallRules(), res)
    }

    /** Fatigue set with the highest membership (Low / Moderate / High / Severe). */
    fun fatigueLevel(f: Double): String = fatigueVar.fuzzify(f).maxByOrNull { it.value }!!.key

    fun normaliseContext(ctx: ContextReading): Map<String, Double> {
        val c = cfg.context
        val assumed = ctx.speedKmh == null
        val speed = if (assumed) c.assumedSpeedKmh else ctx.speedKmh!!
        return linkedMapOf(
            "speed" to Num.piecewise(c.speedKmh, speed),
            "motion" to Num.piecewise(c.motionG, ctx.motionG ?: 0.0),
            "duration" to Num.piecewise(c.drivingHours, ctx.drivingS / 3600.0),
            "speed_assumed" to if (assumed) 1.0 else 0.0,
        )
    }

    fun assess(sev: SeverityReport, ctx: ContextReading): RiskAssessment {
        val cx = normaliseContext(ctx)
        val inputs = linkedMapOf(
            "eye_closure" to sev.drowsiness.score,
            "yawning" to sev.yawning.score,
            "gaze" to sev.gazeDeviation.score,
            "phone" to sev.phoneUsage.score,
        ).apply { putAll(cx) }
        val f = fatigue.infer(mapOf("eye_closure" to inputs.getValue("eye_closure"), "yawning" to inputs.getValue("yawning"), "duration" to cx.getValue("duration")))
        val d = distraction.infer(mapOf("gaze" to inputs.getValue("gaze"), "phone" to inputs.getValue("phone")))
        val c = context.infer(mapOf("speed" to cx.getValue("speed"), "motion" to cx.getValue("motion")))
        val r = overall.infer(mapOf("fatigue" to f.value, "distraction" to d.value, "context" to c.value))
        val top = r.firing.filter { it.second > 0 }.sortedBy { -it.second }.take(3)
        return RiskAssessment(
            fatigue = Num.pyRound(f.value, 4),
            distraction = Num.pyRound(d.value, 4),
            context = Num.pyRound(c.value, 4),
            risk = Num.pyRound(r.value, 2),
            tier = tiers.update(r.value),
            rawTier = tierFor(r.value, cfg.risk.tierBounds),
            fatigueLevel = fatigueLevel(f.value),
            inputs = inputs.mapValues { Num.pyRound(it.value, 4) },
            topRules = top.map { (rule, alpha) -> "%s [%.2f]".format(rule.text("R"), alpha) },
        )
    }
}
