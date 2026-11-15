package edu.dlsu.esca_3_2526_c3.dms

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Stage 2: numbers from the AI outputs (Kotlin port of dms/features/).
 *   EAR = (|p2 - p6| + |p3 - p5|) / (2 |p1 - p4|)        eye openness, per eye
 *   MAR = (|q2 - q8| + |q3 - q7| + |q4 - q6|) / (3 |q1 - q5|)   mouth openness
 *   head pose (yaw, pitch, roll) from six landmarks and a generic 3-D face model
 */

object Geometry {
    // p1..p6: p1/p4 eye corners, p2/p3 upper lid, p6/p5 lower lid (478-point MediaPipe mesh)
    val RIGHT_EYE_EAR = intArrayOf(33, 160, 158, 133, 153, 144) // subject's right eye (image left)
    val LEFT_EYE_EAR = intArrayOf(362, 385, 387, 263, 373, 380) // subject's left eye (image right)
    // q1..q8: q1/q5 inner-lip corners, q2-q4 upper inner lip, q6-q8 lower inner lip
    val MOUTH_MAR = intArrayOf(78, 81, 13, 311, 308, 402, 14, 178)

    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])

    fun eyeAspectRatio(pts: Array<DoubleArray>, idx: IntArray): Double {
        val horizontal = dist(pts[idx[0]], pts[idx[3]])
        if (horizontal <= 1e-6) return 0.0
        return (dist(pts[idx[1]], pts[idx[5]]) + dist(pts[idx[2]], pts[idx[4]])) / (2.0 * horizontal)
    }

    fun eyeWidth(pts: Array<DoubleArray>, idx: IntArray): Double = dist(pts[idx[0]], pts[idx[3]])

    fun mouthAspectRatio(pts: Array<DoubleArray>, idx: IntArray = MOUTH_MAR): Double {
        val horizontal = dist(pts[idx[0]], pts[idx[4]])
        if (horizontal <= 1e-6) return 0.0
        return (dist(pts[idx[1]], pts[idx[7]]) + dist(pts[idx[2]], pts[idx[6]]) + dist(pts[idx[3]], pts[idx[5]])) / (3.0 * horizontal)
    }
}

/**
 * Head pose from six landmarks (dms/features/head_pose.py).
 *
 * The desktop code uses OpenCV solvePnP with SQPnP, which finds the rotation that
 * minimises the object-space error: the squared distances of the rotated face-model
 * points from the lines of sight through the landmarks. This port minimises the same
 * error (Levenberg-Marquardt from several starting poses, translation solved in closed
 * form) and converts the rotation with a port of OpenCV's RQDecomp3x3, so both give
 * the same angles. Sign convention: yaw > 0 when the face turns toward the image's
 * right, pitch > 0 when it tilts up, roll > 0 clockwise in the image.
 */
object HeadPose {
    // MediaPipe indices: nose tip, chin, image-left eye corner, image-right eye corner, mouth corners
    val POSE_LANDMARKS = intArrayOf(1, 152, 33, 263, 61, 291)
    // Generic face model (nose tip at the origin), OpenCV camera axes (x right, y down, z away)
    val MODEL_POINTS = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 330.0, 65.0),
        doubleArrayOf(-225.0, -170.0, 135.0),
        doubleArrayOf(225.0, -170.0, 135.0),
        doubleArrayOf(-150.0, 150.0, 125.0),
        doubleArrayOf(150.0, 150.0, 125.0),
    )

    /** (yaw, pitch, roll) in degrees, or null if the pose cannot be solved. */
    fun estimate(landmarks: Array<DoubleArray>, width: Int, height: Int): DoubleArray? {
        val f = width.toDouble()
        val cx = width / 2.0
        val cy = height / 2.0
        val rays = Array(6) { i ->
            val p = landmarks[POSE_LANDMARKS[i]]
            doubleArrayOf((p[0] - cx) / f, (p[1] - cy) / f, 1.0)
        }
        val r = solveRotation(rays) ?: return null
        return rotationToEuler(r)
    }

    /** Rotation minimising the object-space error (SQPnP's objective). */
    fun solveRotation(rays: Array<DoubleArray>): Array<DoubleArray>? {
        // A_i = I - m m^T / (m^T m): projector onto the plane orthogonal to line of sight i
        val proj = rays.map { m ->
            val n = m[0] * m[0] + m[1] * m[1] + m[2] * m[2]
            Array(3) { a -> DoubleArray(3) { b -> (if (a == b) 1.0 else 0.0) - m[a] * m[b] / n } }
        }
        val sumA = Array(3) { a -> DoubleArray(3) { b -> proj.sumOf { it[a][b] } } }
        val sumAInv = Mat3.inverse(sumA) ?: return null

        fun residuals(r: Array<DoubleArray>): DoubleArray {
            val rx = MODEL_POINTS.map { Mat3.mulVec(r, it) }
            // optimal translation for this rotation: t = -(sum A_i)^-1 sum A_i R X_i
            val s = DoubleArray(3)
            for (i in rx.indices) {
                val v = Mat3.mulVec(proj[i], rx[i])
                for (k in 0..2) s[k] += v[k]
            }
            val t = Mat3.mulVec(sumAInv, s).map { -it }
            val out = DoubleArray(3 * rx.size)
            for (i in rx.indices) {
                val p = doubleArrayOf(rx[i][0] + t[0], rx[i][1] + t[1], rx[i][2] + t[2])
                val e = Mat3.mulVec(proj[i], p)
                for (k in 0..2) out[3 * i + k] = e[k]
            }
            return out
        }

        fun depthOk(r: Array<DoubleArray>): Boolean {
            // the face must be in front of the camera (positive depth of the model centre)
            val rx = MODEL_POINTS.map { Mat3.mulVec(r, it) }
            val s = DoubleArray(3)
            for (i in rx.indices) {
                val v = Mat3.mulVec(proj[i], rx[i])
                for (k in 0..2) s[k] += v[k]
            }
            val t = Mat3.mulVec(sumAInv, s).map { -it }
            return rx.all { it[2] + t[2] > 0.0 }
        }

        fun cost(res: DoubleArray) = res.sumOf { it * it }

        var best: Array<DoubleArray>? = null
        var bestCost = Double.MAX_VALUE
        for (start in STARTS) {
            var r = Mat3.rodrigues(start)
            var res = residuals(r)
            var c = cost(res)
            var lambda = 1e-3
            for (iter in 0 until 100) {
                // numerical Jacobian w.r.t. a small rotation applied on the left: R' = exp(w) R
                val jac = Array(res.size) { DoubleArray(3) }
                val h = 1e-7
                for (k in 0..2) {
                    val w = DoubleArray(3); w[k] = h
                    val rp = residuals(Mat3.mul(Mat3.rodrigues(w), r))
                    w[k] = -h
                    val rm = residuals(Mat3.mul(Mat3.rodrigues(w), r))
                    for (i in res.indices) jac[i][k] = (rp[i] - rm[i]) / (2 * h)
                }
                val jtj = Array(3) { a -> DoubleArray(3) { b -> jac.indices.sumOf { jac[it][a] * jac[it][b] } } }
                val jtr = DoubleArray(3) { a -> jac.indices.sumOf { jac[it][a] * res[it] } }
                var improved = false
                var converged = false
                while (lambda < 1e12) {
                    val m = Array(3) { a -> DoubleArray(3) { b -> jtj[a][b] + if (a == b) lambda * (jtj[a][a] + 1e-12) else 0.0 } }
                    val inv = Mat3.inverse(m) ?: break
                    val step = Mat3.mulVec(inv, jtr).map { -it }.toDoubleArray()
                    val rNew = Mat3.mul(Mat3.rodrigues(step), r)
                    val resNew = residuals(rNew)
                    val cNew = cost(resNew)
                    if (cNew < c) {
                        converged = sqrt(step.sumOf { it * it }) < 1e-12 || (c - cNew) < 1e-15 * (1 + c)
                        r = rNew; res = resNew; c = cNew
                        lambda = maxOf(lambda / 10, 1e-12)
                        improved = true
                        break
                    }
                    lambda *= 10
                }
                if (!improved || converged) break
            }
            if (c < bestCost && depthOk(r)) {
                bestCost = c
                best = r
            }
        }
        return best
    }

    // starting rotations (Rodrigues vectors): straight on, turned left/right, tilted up/down
    private val STARTS: List<DoubleArray> = buildList {
        for (yaw in listOf(0.0, 35.0, -35.0, 70.0, -70.0)) for (pitch in listOf(0.0, 30.0, -30.0)) {
            add(Mat3.rvecFromYawPitch(yaw, pitch))
        }
    }

    /** OpenCV RQDecomp3x3 -> (yaw, pitch, roll) with the desktop sign convention: (-ry, -rx, rz). */
    fun rotationToEuler(rmat: Array<DoubleArray>): DoubleArray {
        val e = rqDecompEuler(rmat)
        return doubleArrayOf(-e[1], -e[0], e[2])
    }

    /** Port of OpenCV cvRQDecomp3x3: Euler angles (x, y, z) in degrees of the Givens rotations. */
    fun rqDecompEuler(mIn: Array<DoubleArray>): DoubleArray {
        val eps = Math.ulp(1.0) // DBL_EPSILON
        val m = Array(3) { mIn[it].copyOf() }
        // Qx
        var s = m[2][1]; var c = m[2][2]
        var z = 1.0 / sqrt(c * c + s * s + eps); c *= z; s *= z
        val qx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, s), doubleArrayOf(0.0, -s, c))
        var r = Mat3.mul(m, qx)
        r[2][1] = 0.0
        // Qy
        s = -r[2][0]; c = r[2][2]
        z = 1.0 / sqrt(c * c + s * s + eps); c *= z; s *= z
        val qy = arrayOf(doubleArrayOf(c, 0.0, -s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(s, 0.0, c))
        val m2 = Mat3.mul(r, qy)
        m2[2][0] = 0.0
        // Qz
        s = m2[1][0]; c = m2[1][1]
        z = 1.0 / sqrt(c * c + s * s + eps); c *= z; s *= z
        var qz = arrayOf(doubleArrayOf(c, s, 0.0), doubleArrayOf(-s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        r = Mat3.mul(m2, qz)
        r[1][0] = 0.0
        // decomposition ambiguity: diagonal of R positive (except the last entry)
        if (r[0][0] < 0) {
            if (r[1][1] < 0) {
                qz[0][0] *= -1; qz[0][1] *= -1; qz[1][0] *= -1; qz[1][1] *= -1
            } else {
                qz = Mat3.transpose(qz)
                qy[0][0] *= -1; qy[0][2] *= -1; qy[2][0] *= -1; qy[2][2] *= -1
            }
        } else if (r[1][1] < 0) {
            qz = Mat3.transpose(qz)
            val qyT = Mat3.transpose(qy)
            for (i in 0..2) qy[i] = qyT[i]
            qx[1][1] *= -1; qx[1][2] *= -1; qx[2][1] *= -1; qx[2][2] *= -1
        }
        val deg = 180.0 / PI
        val ex = acos(qx[1][1].coerceIn(-1.0, 1.0)) * (if (qx[1][2] >= 0) 1 else -1) * deg
        val ey = acos(qy[0][0].coerceIn(-1.0, 1.0)) * (if (qy[2][0] >= 0) 1 else -1) * deg
        val ez = acos(qz[0][0].coerceIn(-1.0, 1.0)) * (if (qz[0][1] >= 0) 1 else -1) * deg
        return doubleArrayOf(ex, ey, ez)
    }
}

object Mat3 {
    fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

    fun mulVec(a: Array<DoubleArray>, v: DoubleArray) =
        DoubleArray(3) { i -> a[i][0] * v[0] + a[i][1] * v[1] + a[i][2] * v[2] }

    fun transpose(a: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }

    fun inverse(a: Array<DoubleArray>): Array<DoubleArray>? {
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        if (abs(det) < 1e-300) return null
        val inv = Array(3) { DoubleArray(3) }
        inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det
        inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det
        inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det
        inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det
        inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det
        inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det
        inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det
        inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det
        inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det
        return inv
    }

    /** Rotation matrix of a Rodrigues vector (axis * angle in radians). */
    fun rodrigues(w: DoubleArray): Array<DoubleArray> {
        val theta = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (theta < 1e-15) {
            // first-order: I + [w]x
            return arrayOf(
                doubleArrayOf(1.0, -w[2], w[1]),
                doubleArrayOf(w[2], 1.0, -w[0]),
                doubleArrayOf(-w[1], w[0], 1.0),
            )
        }
        val kx = w[0] / theta; val ky = w[1] / theta; val kz = w[2] / theta
        val c = cos(theta); val s = sin(theta); val v = 1 - c
        return arrayOf(
            doubleArrayOf(c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s),
            doubleArrayOf(ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s),
            doubleArrayOf(kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v),
        )
    }

    /** Rodrigues vector of a face turned by yaw (toward image right) and tilted by pitch (up), degrees. */
    fun rvecFromYawPitch(yawDeg: Double, pitchDeg: Double): DoubleArray {
        // turning toward image right = negative rotation about the camera y axis (head_pose.py)
        val ry = rodrigues(doubleArrayOf(0.0, -yawDeg * PI / 180, 0.0))
        val rx = rodrigues(doubleArrayOf(-pitchDeg * PI / 180, 0.0, 0.0))
        return toRodrigues(mul(rx, ry))
    }

    fun toRodrigues(r: Array<DoubleArray>): DoubleArray {
        val tr = (r[0][0] + r[1][1] + r[2][2] - 1) / 2
        val theta = acos(tr.coerceIn(-1.0, 1.0))
        if (theta < 1e-12) return DoubleArray(3)
        val k = 1 / (2 * sin(theta))
        return doubleArrayOf((r[2][1] - r[1][2]) * k * theta, (r[0][2] - r[2][0]) * k * theta, (r[1][0] - r[0][1]) * k * theta)
    }
}

/** Stage 2 (dms/features/extractor.py): turn the AI outputs into FrameFeatures. No decisions here. */
class FeatureExtractor(private val cfg: ObjectDetectorConfig, private val fusion: FusionModel? = null) {

    /** A phone counts only near the driver (not on the passenger seat or a dashboard mount). */
    fun phoneInDriverRoi(box: DoubleArray, face: FaceObservation?, frameH: Int): Boolean {
        if (face == null) return true
        val (fx1, fy1, fx2, fy2) = face.bbox.toList()
        val fw = fx2 - fx1; val fh = fy2 - fy1
        val fcx = (fx1 + fx2) / 2.0
        val cx = (box[0] + box[2]) / 2.0; val cy = (box[1] + box[3]) / 2.0
        val withinX = abs(cx - fcx) <= cfg.roiWidthFactor * fw
        val withinY = fy1 - cfg.roiAboveFactor * fh <= cy && cy <= frameH
        return withinX && withinY
    }

    fun insideFace(box: DoubleArray, face: FaceObservation, margin: Double): Boolean {
        val (fx1, fy1, fx2, fy2) = face.bbox.toList()
        val mx = (fx2 - fx1) * margin; val my = (fy2 - fy1) * margin
        val cx = (box[0] + box[2]) / 2.0; val cy = (box[1] + box[3]) / 2.0
        return fx1 - mx <= cx && cx <= fx2 + mx && fy1 - my <= cy && cy <= fy2 + my
    }

    /** Best positive and negative class confidences -> 0..1 (0.5 = undecided). */
    fun twoClassScore(positive: Double?, negative: Double?): Double? {
        if (positive == null && negative == null) return null
        return (1.0 + (positive ?: 0.0) - (negative ?: 0.0)) / 2.0
    }

    fun extract(p: PerceptionResult, t: Double): FrameFeatures {
        val face = p.face
        var phoneConf = 0.0
        var phoneBoxes = 0
        for (det in p.detections) {
            if (det.role != "phone") continue
            det.inDriverRoi = phoneInDriverRoi(det.box, face, p.height)
            if (det.inDriverRoi) {
                phoneBoxes += 1
                phoneConf = maxOf(phoneConf, det.confidence)
            }
        }
        if (face == null) return FrameFeatures(t = t, facePresent = false, phoneConf = phoneConf, phoneBoxes = phoneBoxes)

        val parts = mutableMapOf<String, MutableList<Double>>("eye_open" to mutableListOf(), "eye_closed" to mutableListOf(),
            "yawn" to mutableListOf(), "no_yawn" to mutableListOf())
        for (det in p.detections) {
            val role = det.role ?: continue
            if (role in parts && det.confidence >= cfg.conf) {
                det.inDriverRoi = insideFace(det.box, face, cfg.facePartMargin)
                if (det.inDriverRoi) parts.getValue(role).add(det.confidence)
            }
        }
        val roles = p.detectorRoles
        var eyeClosedYolo: Double? = null
        if ("eye_open" in roles || "eye_closed" in roles) {
            eyeClosedYolo = twoClassScore(parts.getValue("eye_closed").maxOrNull(), parts.getValue("eye_open").maxOrNull())
        }
        var yawnYolo: Double? = null
        if ("yawn" in roles) {
            yawnYolo = twoClassScore(parts.getValue("yawn").maxOrNull(), parts.getValue("no_yawn").maxOrNull()) ?: 0.0
        }

        val pts = face.landmarks
        val earR = Geometry.eyeAspectRatio(pts, Geometry.RIGHT_EYE_EAR)
        val earL = Geometry.eyeAspectRatio(pts, Geometry.LEFT_EYE_EAR)
        val pose = HeadPose.estimate(pts, p.width, p.height)
        val bs = face.blendshapes
        val blink = if ("eyeBlinkLeft" in bs && "eyeBlinkRight" in bs) (bs.getValue("eyeBlinkLeft") + bs.getValue("eyeBlinkRight")) / 2.0 else null
        val lookDown = if ("eyeLookDownLeft" in bs && "eyeLookDownRight" in bs) (bs.getValue("eyeLookDownLeft") + bs.getValue("eyeLookDownRight")) / 2.0 else null
        val features = FrameFeatures(
            t = t,
            facePresent = true,
            ear = (earL + earR) / 2.0,
            earLeft = earL,
            earRight = earR,
            mar = Geometry.mouthAspectRatio(pts),
            yaw = pose?.get(0),
            pitch = pose?.get(1),
            roll = pose?.get(2),
            blink = blink,
            jawOpen = bs["jawOpen"],
            eyeClosedProb = p.eyeClosedProb,
            eyeClosedYolo = eyeClosedYolo,
            yawnYolo = yawnYolo,
            phoneConf = phoneConf,
            phoneBoxes = phoneBoxes,
            eyeWidthPx = (Geometry.eyeWidth(pts, Geometry.LEFT_EYE_EAR) + Geometry.eyeWidth(pts, Geometry.RIGHT_EYE_EAR)) / 2.0,
            lookDown = lookDown,
        )
        val model = fusion ?: return features
        val probs = model.predict(FusionInputs.of(face, features))
        return features.copy(eyeClosedFusion = probs["eye_closed"], yawnFusion = probs["yawn"])
    }
}
