package com.gatememory.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Base64

class GateTagStore(context: Context) {
    private val tagsDir = File(context.filesDir, "gate_tags").apply { mkdirs() }
    private val indexFile = File(tagsDir, "index.json")

    fun loadTags(): List<GateTag> {
        if (!indexFile.exists()) return emptyList()
        val array = JSONArray(indexFile.readText())
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                add(
                    GateTag(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        noteText = json.optString("noteText"),
                        transcript = json.optString("transcript").ifBlank { null },
                        isTranscribing = json.optBoolean("isTranscribing", false),
                        imagePath = json.getString("imagePath"),
                        audioPath = json.optString("audioPath").ifBlank { null },
                        descriptorPath = json.getString("descriptorPath"),
                        createdAtMillis = json.getLong("createdAtMillis"),
                    ),
                )
            }
        }
    }

    fun saveTag(
        name: String,
        noteText: String,
        transcript: String?,
        isTranscribing: Boolean,
        image: Mat,
        descriptors: Mat,
        audioPath: String?,
    ): GateTag {
        val id = "tag_${System.currentTimeMillis()}"
        val imagePath = File(tagsDir, "$id.jpg").absolutePath
        val descriptorPath = File(tagsDir, "$id.descriptors.json").absolutePath

        Imgcodecs.imwrite(imagePath, image)
        writeDescriptors(File(descriptorPath), descriptors)

        val tag = GateTag(
            id = id,
            name = name,
            noteText = noteText,
            transcript = transcript,
            isTranscribing = isTranscribing,
            imagePath = imagePath,
            audioPath = audioPath,
            descriptorPath = descriptorPath,
            createdAtMillis = System.currentTimeMillis(),
        )
        writeTags(loadTags() + tag)
        return tag
    }

    fun createAudioFile(): File = File(tagsDir, "note_${System.currentTimeMillis()}.wav")

    fun clearAll() {
        tagsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    fun updateTranscript(tagId: String, transcript: String?, isTranscribing: Boolean) {
        val updatedTags = loadTags().map { tag ->
            if (tag.id == tagId) {
                tag.copy(transcript = transcript, isTranscribing = isTranscribing)
            } else {
                tag
            }
        }
        writeTags(updatedTags)
    }

    fun loadDescriptors(tag: GateTag): Mat? {
        val file = File(tag.descriptorPath)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val rows = json.getInt("rows")
        val cols = json.getInt("cols")
        val bytes = Base64.getDecoder().decode(json.getString("data"))
        if (rows <= 0 || cols <= 0 || bytes.isEmpty()) return null
        return Mat(rows, cols, CvType.CV_8U).apply { put(0, 0, bytes) }
    }

    private fun writeTags(tags: List<GateTag>) {
        val array = JSONArray()
        tags.forEach { tag ->
            array.put(
                JSONObject()
                    .put("id", tag.id)
                    .put("name", tag.name)
                    .put("noteText", tag.noteText)
                    .put("transcript", tag.transcript.orEmpty())
                    .put("isTranscribing", tag.isTranscribing)
                    .put("imagePath", tag.imagePath)
                    .put("audioPath", tag.audioPath.orEmpty())
                    .put("descriptorPath", tag.descriptorPath)
                    .put("createdAtMillis", tag.createdAtMillis),
            )
        }
        indexFile.writeText(array.toString(2))
    }

    private fun writeDescriptors(file: File, descriptors: Mat) {
        val bytes = ByteArray((descriptors.total() * descriptors.elemSize()).toInt())
        descriptors.get(0, 0, bytes)
        val json = JSONObject()
            .put("rows", descriptors.rows())
            .put("cols", descriptors.cols())
            .put("data", Base64.getEncoder().encodeToString(bytes))
        file.writeText(json.toString())
    }
}
