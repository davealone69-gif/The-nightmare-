package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject

/** Human-entered examples are practice material, not proof of rights or a trained model. */
data class TrainingExample(
    val id: String,
    val instruction: String,
    val reference: String,
    val source: String,
    val approved: Boolean = false,
)

object TrainingExamples {
    const val MAX_EXAMPLES = 100
    const val MAX_FIELD = 4000
    const val MAX_BYTES = 1024 * 1024

    fun validate(example: TrainingExample) {
        require(example.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid example ID." }
        require(example.instruction.isNotBlank() && example.instruction.length <= MAX_FIELD) { "Enter an instruction of at most $MAX_FIELD characters." }
        require(example.reference.isNotBlank() && example.reference.length <= MAX_FIELD) { "Enter a reference answer of at most $MAX_FIELD characters." }
        require(example.source.isNotBlank() && example.source.length <= 500) { "Enter a source and permission note of at most 500 characters." }
    }

    fun encode(examples: List<TrainingExample>): ByteArray {
        require(examples.size <= MAX_EXAMPLES) { "At most $MAX_EXAMPLES examples are supported." }
        require(examples.map { it.id }.toSet().size == examples.size) { "Duplicate example ID." }
        val rows = JSONArray()
        examples.forEach { item ->
            validate(item)
            rows.put(JSONObject().put("id", item.id).put("instruction", item.instruction)
                .put("reference", item.reference).put("source", item.source).put("approved", item.approved))
        }
        val bytes = JSONObject().put("version", 1).put("examples", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Example collection exceeds the size limit." }
        return bytes
    }

    fun decode(bytes: ByteArray): List<TrainingExample> {
        require(bytes.size <= MAX_BYTES) { "Example collection exceeds the size limit." }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(text)
        require(root.get("version") == 1) { "Unsupported example collection version." }
        val rows = root.getJSONArray("examples")
        require(rows.length() <= MAX_EXAMPLES) { "Too many examples." }
        val result = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun string(key: String): String = row.get(key) as? String ?: error("Invalid $key in example.")
            val example = TrainingExample(string("id"), string("instruction"), string("reference"), string("source"),
                row.get("approved") as? Boolean ?: error("Invalid approval state."))
            validate(example)
            example
        }
        require(result.map { it.id }.toSet().size == result.size) { "Duplicate example ID." }
        return result
    }
}

/** Prompts are practice tasks, not a claim about features in any particular Mega Hub build. */
object TrainingCurriculum {
    val lessons = listOf(
        "Rendering fundamentals" to "Explain a step-by-step method for diagnosing a dark or invisible 3D avatar in an Android OpenGL ES renderer. List what evidence to collect before changing settings.",
        "Shaders and materials" to "Explain how to investigate incorrect PBR roughness, metallic and color-space behavior in a glTF avatar. State assumptions and propose safe tests.",
        "Photorealistic avatars" to "Describe a workflow for improving the realism of a synthetic 3D avatar, covering anatomy, materials, lighting and visual evaluation. Distinguish evidence from assumptions.",
        "Phone performance" to "Suggest how to profile an Android avatar renderer for memory use, frame time and thermal throttling on a Samsung Galaxy S24. Do not invent measurements.",
    )
}
