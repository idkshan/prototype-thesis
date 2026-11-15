package edu.dlsu.esca_3_2526_c3.monitor

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import edu.dlsu.esca_3_2526_c3.dms.FaceModelConfig
import edu.dlsu.esca_3_2526_c3.dms.FaceObservation
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage 1 AI: MediaPipe Face Landmarker (the same face_landmarker.task as the desktop):
 * 478 landmarks + 52 blendshapes. Landmarks are converted to pixels exactly as in
 * dms/perception/face_landmarker.py (x * width, y * height, z * width).
 */
class FaceAi(context: Context, cfg: FaceModelConfig) : AutoCloseable {
    private val landmarker: FaceLandmarker
    private var lastTs = -1L

    init {
        val bytes = context.assets.open(cfg.modelPath).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1) // the driver
            .setMinFaceDetectionConfidence(cfg.minDetectionConfidence.toFloat())
            .setMinFacePresenceConfidence(cfg.minPresenceConfidence.toFloat())
            .setMinTrackingConfidence(cfg.minTrackingConfidence.toFloat())
            .setOutputFaceBlendshapes(true)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    /** bitmap must be ARGB_8888; tMs = frame time in milliseconds (must increase). */
    fun detect(bitmap: Bitmap, tMs: Long): FaceObservation? {
        val ts = maxOf(tMs, lastTs + 1)
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        val pts = faces[0].map { doubleArrayOf(it.x() * w, it.y() * h, it.z() * w) }.toTypedArray()
        val blend = mutableMapOf<String, Double>()
        val bs = result.faceBlendshapes()
        if (bs.isPresent && bs.get().isNotEmpty()) {
            for (c in bs.get()[0]) blend[c.categoryName()] = c.score().toDouble()
        }
        return FaceObservation.fromLandmarks(pts, blend)
    }

    override fun close() = landmarker.close()
}
