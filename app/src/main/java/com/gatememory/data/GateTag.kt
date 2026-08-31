package com.gatememory.data

data class GateTag(
    val id: String,
    val name: String,
    val noteText: String,
    val transcript: String?,
    val isTranscribing: Boolean,
    val imagePath: String,
    val audioPath: String?,
    val descriptorPath: String,
    val createdAtMillis: Long,
)
