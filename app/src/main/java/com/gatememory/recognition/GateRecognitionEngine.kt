package com.gatememory.recognition

import com.gatememory.data.GateTag
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB

class GateRecognitionEngine(
    private val config: RecognitionConfig = RecognitionConfig(),
) {
    private val orb = ORB.create(config.orbFeatureCount)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    fun extractFeatures(grayFrame: Mat): FeatureResult {
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(grayFrame, Mat(), keypoints, descriptors)
        return FeatureResult(keypoints = keypoints, descriptors = descriptors)
    }

    fun score(liveDescriptors: Mat, savedTags: List<SavedTagFeatures>): RecognitionResult {
        if (liveDescriptors.empty() || liveDescriptors.rows() == 0) {
            return RecognitionResult(bestTag = null, confidence = 0f, goodMatches = 0)
        }

        var bestTag: GateTag? = null
        var bestConfidence = 0f
        var bestGoodMatches = 0

        savedTags.forEach { saved ->
            if (saved.descriptors.empty() || saved.descriptors.rows() < 2) return@forEach
            val matches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(liveDescriptors, saved.descriptors, matches, 2)

            val goodMatches = matches.count { pair ->
                val values = pair.toArray()
                values.size == 2 && values[0].distance < config.ratioTestThreshold * values[1].distance
            }
            val confidence = goodMatches.toFloat() / liveDescriptors.rows().toFloat()

            if (confidence > bestConfidence) {
                bestTag = saved.tag
                bestConfidence = confidence
                bestGoodMatches = goodMatches
            }
        }

        return RecognitionResult(
            bestTag = bestTag,
            confidence = bestConfidence,
            goodMatches = bestGoodMatches,
        )
    }
}

data class FeatureResult(
    val keypoints: MatOfKeyPoint,
    val descriptors: Mat,
)

data class SavedTagFeatures(
    val tag: GateTag,
    val descriptors: Mat,
)

data class RecognitionResult(
    val bestTag: GateTag?,
    val confidence: Float,
    val goodMatches: Int,
) {
    fun isMatch(config: RecognitionConfig): Boolean {
        return bestTag != null &&
            confidence >= config.confidenceThreshold &&
            goodMatches >= config.minimumGoodMatches
    }
}
