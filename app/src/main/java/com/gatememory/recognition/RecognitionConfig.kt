package com.gatememory.recognition

data class RecognitionConfig(
    val orbFeatureCount: Int = 1_000,
    val ratioTestThreshold: Float = 0.6f,
    val confidenceThreshold: Float = 0.10f,
    val minimumTagKeypoints: Int = 20,
    val minimumLiveKeypoints: Int = 20,
    val minimumGoodMatches: Int = 8,
)
