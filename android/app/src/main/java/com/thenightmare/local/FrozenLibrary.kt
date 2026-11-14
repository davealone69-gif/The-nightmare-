package com.thenightmare.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

class FrozenLibraryException(
    val reason: LibraryFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class FrozenLibrary(private val root: File, private val expectedSnapshotId: String) {
    data class ValidatedSnapshot(val snapshotId: String, val passages: List<RetrievedPassage>, val sourceCount: Int)
    private data class Document(val reference: SourceReference, val text: String)

    fun info(): LibraryInfo {
        if (!root.exists()) return LibraryInfo(false, failureReason = LibraryFailureReason.LIBRARY_UNAVAILABLE)
        return try {
            val docs = loadDocuments()
            LibraryInfo(true, docs.first, docs.second.size, "Snapshot verified: ${docs.first}")
        } catch (exception: Exception) {
            LibraryInfo(
                false,
                message = "Frozen library rejected: ${exception.message ?: "invalid snapshot"}",
                failureReason = classifyFailure(),
            )
        }
    }

    fun search(query: String): ValidatedSnapshot {
        require(query.isNotBlank()) { "query must not be empty" }
        val (snapshotId, documents) = try {
            loadDocuments()
        } catch (exception: FrozenLibraryException) {
            throw exception
        } catch (exception: Exception) {
            throw FrozenLibraryException(classifyFailure(), exception.message ?: "frozen library could not be loaded", exception)
        }
        val queryTerms = tokens(query).toSet()
        if (queryTerms.isEmpty()) return ValidatedSnapshot(snapshotId, emptyList(), documents.size)
        val ranked = documents.mapNotNull { document ->
            val matched = queryTerms.intersect(tokens(document.reference.title + " " + document.text).toSet())
            if (matched.isEmpty()) null else Triple(document, matched, matched.size.toDouble() / queryTerms.size)
        }.sortedWith(compareByDescending<Triple<Document, Set<String>, Double>> { it.third }
            .thenByDescending { it.second.size }
            .thenBy { it.first.reference.sourceId })
            .take(MAX_RESULTS)
            .map { (document, matched) -> RetrievedPassage(document.reference, excerpt(document.text, matched)) }
        return ValidatedSnapshot(snapshotId, ranked, documents.size)
    }

    private fun loadDocuments(): Pair<String, List<Document>> {
        require(root.isDirectory) { "frozen library directory is unavailable" }
        require(!root.isSymbolicLinkCompat()) { "frozen library root cannot be a symbolic link" }
        require(expectedSnapshotId.matches(Regex("snapshot-[0-9a-f]{16}"))) {
            "an expected snapshot pin is required"
        }
        val allFiles = mutableMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        fun visit(directory: File, relative: String, depth: Int) {
            require(depth <= 8) { "frozen library nesting is too deep" }
            val children = directory.listFiles() ?: error("cannot list frozen library directory")
            children.sortedBy { it.name }.forEach { child ->
                count += 1
                require(count <= MAX_SOURCES + 1) { "too many frozen library entries" }
                require(!child.isSymbolicLinkCompat()) { "symbolic links are not allowed" }
                require(child.name !in setOf("", ".", "..") && '/' !in child.name && '\\' !in child.name) {
                    "invalid frozen library entry name"
                }
                val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (child.isDirectory) {
                    visit(child, childPath, depth + 1)
                } else {
                    require(child.isFile) { "only regular files and directories are allowed" }
                    val limit = if (childPath == MANIFEST_NAME) MAX_MANIFEST_BYTES else MAX_SOURCE_BYTES
                    val bytes = child.inputStream().use { readBounded(it, limit) }
                    total += bytes.size
                    require(total <= MAX_LIBRARY_BYTES + MAX_MANIFEST_BYTES) { "frozen library exceeds the size limit" }
                    allFiles[childPath] = bytes
                }
            }
        }
        visit(root, "", 0)
        val manifestBytes = allFiles[MANIFEST_NAME] ?: error("frozen library manifest is missing")
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        require(manifestText.toByteArray(Charsets.UTF_8).contentEquals(manifestBytes)) { "frozen library manifest is not valid UTF-8" }
        val manifest = JSONObject(manifestText)
        require(manifest.keys().asSequence().toSet() == setOf("schema_version", "snapshot_id", "sources")) {
            "frozen library manifest has an invalid structure"
        }
        require(manifest.optString("schema_version") == "1.0") { "unsupported frozen library manifest version" }
        val manifestPin = manifest.optString("snapshot_id")
        require(manifestPin.matches(Regex("snapshot-[0-9a-f]{16}"))) { "frozen library snapshot id is invalid" }
        require(manifestPin == expectedSnapshotId) { "frozen library snapshot id does not match the configured pin" }
        val sources = manifest.optJSONArray("sources") ?: error("frozen library sources are invalid")
        require(sources.length() in 1..MAX_SOURCES) { "frozen library must contain between 1 and $MAX_SOURCES sources" }
        val declared = mutableSetOf(MANIFEST_NAME)
        val sourceIds = mutableSetOf<String>()
        var sourceBytes = 0L
        val documents = mutableListOf<Document>()
        for (index in 0 until sources.length()) {
            val item = sources.optJSONObject(index) ?: error("frozen library source entry is invalid")
            require(item.keys().asSequence().toSet() == setOf("id", "title", "path", "sha256")) {
                "frozen library source entry has an invalid structure"
            }
            val id = item.getString("id")
            val title = item.getString("title").trim()
            val relativePath = item.getString("path")
            val expectedHash = item.getString("sha256")
            require(id.matches(Regex("[A-Z0-9][A-Z0-9_-]{0,63}")) && sourceIds.add(id)) {
                "frozen library source ids must be unique uppercase identifiers"
            }
            require(title.isNotEmpty() && title.length <= 300) { "frozen library source title is invalid" }
            require(validRelativeSourcePath(relativePath)) { "frozen library source path is invalid" }
            require(declared.add(relativePath)) { "frozen library source paths must be unique" }
            require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "frozen library source hash is invalid" }
            val bytes = allFiles[relativePath] ?: error("frozen library source is missing: $relativePath")
            sourceBytes += bytes.size
            require(sourceBytes <= MAX_LIBRARY_BYTES) { "frozen library exceeds the total source size limit" }
            require(JsonCanonical.sha256(bytes) == expectedHash) { "frozen library source digest does not match its manifest" }
            val text = String(bytes, Charsets.UTF_8)
            require(text.isNotBlank()) { "frozen library source is empty" }
            val actual = text.toByteArray(Charsets.UTF_8)
            require(actual.contentEquals(bytes)) { "frozen library source is not valid UTF-8" }
            documents += Document(SourceReference(id, title, relativePath, expectedHash), text)
        }
        require(allFiles.keys == declared) { "frozen library files do not exactly match the manifest" }
        val computedPin = "snapshot-${JsonCanonical.sha256(JsonCanonical.encode(sources).toByteArray(Charsets.UTF_8)).take(16)}"
        require(computedPin == manifestPin) { "frozen library snapshot id does not match its source metadata" }
        makeReadOnlyTree(root)
        return computedPin to documents
    }

    private fun classifyFailure(): LibraryFailureReason =
        if (!root.isDirectory || !expectedSnapshotId.matches(Regex("snapshot-[0-9a-f]{16}"))) {
            LibraryFailureReason.LIBRARY_UNAVAILABLE
        } else {
            LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE
        }

    private fun validRelativeSourcePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || '\\' in path) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        val suffix = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return suffix == "md" || suffix == "txt"
    }

    private fun excerpt(text: String, matchedTerms: Set<String>): String {
        val lines = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (raw in text.lineSequence()) {
            val clean = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (clean.isEmpty()) continue
            val key = clean.lowercase(Locale.ROOT)
            if (key in seen || tokens(clean).none { it in matchedTerms }) continue
            seen += key
            lines += clean
            if (lines.sumOf { it.length + 1 } >= MAX_EXCERPT_CHARS) break
        }
        if (lines.isEmpty()) return text.replace(Regex("\\s+"), " ").take(MAX_EXCERPT_CHARS).trim()
        return lines.joinToString("\n").take(MAX_EXCERPT_CHARS).trim()
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val MAX_SOURCE_BYTES = 1024 * 1024
        const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_SOURCES = 500
        const val MAX_RESULTS = 5
        const val MAX_EXCERPT_CHARS = 1600
        private val stopWords = ("a an and are as at be been but by can could did do does for from had has have he her here hers him his how i if in into is it its may me might more most my no not of on or our ours she should so than that the their them then there these they this those through to too up us was we were what when where which while who why will with would you your yours about according guide source sources cite citing cited citation citations tell explain describe summarize summary say says said need needs use used doesn't don't please local library document documents information")
            .split(Regex("\\s+")).toSet()
        private val tokenPattern = Regex("[a-z0-9][a-z0-9_-]*")

        private fun makeReadOnlyTree(file: File) {
            require(!file.isSymbolicLinkCompat()) { "symbolic links are not allowed" }
            if (file.isDirectory) {
                val children = file.listFiles() ?: error("cannot list frozen library directory")
                children.forEach { makeReadOnlyTree(it) }
            }
            check(file.setWritable(false, false) && !file.canWrite()) {
                "frozen library entries could not be made read-only"
            }
        }

        private fun makeWritableTree(file: File) {
            if (file.isSymbolicLinkCompat()) return
            if (file.isDirectory) file.listFiles()?.forEach { makeWritableTree(it) }
            file.setWritable(true, false)
        }

        private fun tokens(text: String): List<String> = tokenPattern.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }.filter { it !in stopWords && it.length > 1 }.toList()

        private fun File.isSymbolicLinkCompat(): Boolean = try {
            java.nio.file.Files.isSymbolicLink(toPath())
        } catch (_: Exception) {
            false
        }

        private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val amount = input.read(buffer)
                if (amount < 0) break
                require(output.size() + amount <= maxBytes) { "frozen library file exceeds its size limit" }
                output.write(buffer, 0, amount)
            }
            return output.toByteArray()
        }

        fun importFromTree(
            context: Context,
            treeUri: Uri,
            expectedSnapshotId: String,
            replaceExisting: Boolean,
        ): LibraryInfo {
            require(expectedSnapshotId.matches(Regex("snapshot-[0-9a-f]{16}"))) { "enter the pinned snapshot ID first" }
            val resolver = context.contentResolver
            val copied = collectTreeFiles(resolver, treeUri)
            val staging = File(context.filesDir, "library-import-${System.currentTimeMillis()}")
            check(staging.mkdir()) { "cannot create library import staging directory" }
            var total = 0L
            try {
                for ((relative, bytes) in copied) {
                    val target = File(staging, relative)
                    check(target.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath())) { "invalid imported file path" }
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    total += bytes.size
                    require(total <= MAX_LIBRARY_BYTES + MAX_MANIFEST_BYTES) { "frozen library exceeds size limits" }
                }
                val validator = FrozenLibrary(staging, expectedSnapshotId)
        val validated = validator.loadDocuments()
                val current = File(context.filesDir, "library")
                if (current.exists()) {
                    require(replaceExisting) { "an Android library already exists; confirm replacement first" }
                    val backup = File(context.filesDir, "library-replaced-${System.currentTimeMillis()}")
                    check(current.renameTo(backup)) { "could not stage the previous Android library" }
                    if (!staging.renameTo(current)) {
                        backup.renameTo(current)
                        error("could not install the new Android library")
                    }
                    makeWritableTree(backup)
                    check(backup.deleteRecursively()) { "could not remove the replaced Android library backup" }
                } else {
                    check(staging.renameTo(current)) { "could not install the Android library" }
                }
                return LibraryInfo(true, validated.first, validated.second.size, "Imported and verified ${validated.second.size} sources (${validated.first}).")
            } catch (exception: Exception) {
                makeWritableTree(staging)
                staging.deleteRecursively()
                throw exception
            }
        }

        private fun collectTreeFiles(resolver: ContentResolver, treeUri: Uri): Map<String, ByteArray> {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val all = linkedMapOf<String, ByteArray>()
            var count = 0
            fun walk(parentId: String, prefix: String, depth: Int) {
                require(depth <= 8) { "selected library directory is nested too deeply" }
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                resolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ), null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val typeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        count += 1
                        require(count <= MAX_SOURCES + 1) { "too many imported library entries" }
                        val id = cursor.getString(idCol)
                        val name = cursor.getString(nameCol) ?: error("selected file has no name")
                        val mime = cursor.getString(typeCol)
                        require(name !in setOf("", ".", "..") && '/' !in name && '\\' !in name) { "invalid selected filename" }
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, path, depth + 1)
                        } else {
                            require(path == MANIFEST_NAME || validExtension(name)) { "library may contain only manifest.json and .md/.txt sources" }
                            val limit = if (path == MANIFEST_NAME) MAX_MANIFEST_BYTES else MAX_SOURCE_BYTES
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val input = resolver.openInputStream(uri) ?: error("cannot read selected library file: $path")
                            all[path] = input.use { readBounded(it, limit) }
                        }
                    }
                } ?: error("cannot list selected library directory")
            }
            walk(rootId, "", 0)
            return all
        }

        private fun validExtension(name: String): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension == "md" || extension == "txt"
        }
    }
}
