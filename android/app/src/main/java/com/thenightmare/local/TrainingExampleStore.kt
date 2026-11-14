package com.thenightmare.local

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/** A small app-private collection; all operations serialize across Activity instances. */
class TrainingExampleStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "training-examples.json"))

    fun list(): List<TrainingExample> = synchronized(LOCK) { readLocked() }

    fun create(example: TrainingExample) = synchronized(LOCK) {
        require(!example.approved) { "New examples must start as drafts." }
        val current = readLocked()
        require(current.none { it.id == example.id }) { "Example already exists." }
        writeLocked(current + example)
    }

    fun replace(example: TrainingExample) = synchronized(LOCK) {
        val current = readLocked()
        val index = current.indexOfFirst { it.id == example.id }
        require(index >= 0) { "Example no longer exists." }
        // Editing changes the reviewed content, so it always returns to draft.
        writeLocked(current.toMutableList().also { it[index] = example.copy(approved = false) })
    }

    fun approve(id: String, approved: Boolean) = synchronized(LOCK) {
        val current = readLocked()
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "Example no longer exists." }
        writeLocked(current.toMutableList().also { it[index] = it[index].copy(approved = approved) })
    }

    fun delete(id: String) = synchronized(LOCK) {
        val current = readLocked()
        require(current.any { it.id == id }) { "Example no longer exists." }
        writeLocked(current.filterNot { it.id == id })
    }

    private fun readLocked(): List<TrainingExample> {
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            val base = file.baseFile
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) return emptyList()
            throw error
        }
        val bytes = input.use {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(result.size() + n <= TrainingExamples.MAX_BYTES) { "Example collection exceeds the size limit." }
                result.write(buffer, 0, n)
            }
            result.toByteArray()
        }
        return TrainingExamples.decode(bytes)
    }

    private fun writeLocked(examples: List<TrainingExample>) {
        val bytes = TrainingExamples.encode(examples)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object { private val LOCK = Any() }
}
