package com.thenightmare.local

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path

class FrozenLibraryException(
    val reason: LibraryFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class FrozenLibrary(private val root: File, private val expectedSnapshotId: String) {
    data class ValidatedSnapshot(val snapshotId: String, val passages: List<RetrievedPassage>, val sourceCount: Int)
    data class Preparation(val stagingDirectory: File, val snapshotId: String, val sourceCount: Int)
    private data class Document(val reference: SourceReference, val text: String)

    fun info(): LibraryInfo {
        if (!existsNoFollow(root)) return LibraryInfo(false, failureReason = LibraryFailureReason.LIBRARY_UNAVAILABLE)
        return try {
            val (pin, docs) = loadDocuments()
            LibraryInfo(true, pin, docs.size, "Snapshot verified: $pin")
        } catch (exception: Exception) {
            LibraryInfo(false, message = "Frozen library rejected: ${exception.message ?: "invalid snapshot"}", failureReason = classifyFailure())
        }
    }

    fun search(query: String): ValidatedSnapshot {
        require(query.isNotBlank()) { "query must not be empty" }
        val (snapshotId, documents) = try { loadDocuments() } catch (e: FrozenLibraryException) { throw e } catch (e: Exception) {
            throw FrozenLibraryException(classifyFailure(), e.message ?: "frozen library could not be loaded", e)
        }
        val terms = tokens(query).toSet()
        if (terms.isEmpty()) return ValidatedSnapshot(snapshotId, emptyList(), documents.size)
        val ranked = documents.mapNotNull { document ->
            val matched = terms.intersect(tokens(document.reference.title + " " + document.text).toSet())
            if (matched.isEmpty()) null else Triple(document, matched, matched.size.toDouble() / terms.size)
        }.sortedWith(compareByDescending<Triple<Document, Set<String>, Double>> { it.third }.thenByDescending { it.second.size }.thenBy { it.first.reference.sourceId })
            .take(MAX_RESULTS).map { (document, matched) -> RetrievedPassage(document.reference, excerpt(document.text, matched)) }
        return ValidatedSnapshot(snapshotId, ranked, documents.size)
    }

    /** Validates a complete existing tree and returns only its trusted snapshot ID. */
    fun validateForPinRecovery(): String = loadDocuments(allowBlankPin = true).first

    private fun loadDocuments(allowBlankPin: Boolean = false): Pair<String, List<Document>> {
        require(root.isDirectory && !root.isSymbolicLinkCompat()) { "frozen library directory is unavailable" }
        val pin = expectedSnapshotId
        require(allowBlankPin || pin.matches(SNAPSHOT_PATTERN)) { "an expected snapshot pin is required" }
        requireTreeReadOnly(root)
        val allFiles = linkedMapOf<String, ByteArray>(); var total = 0L; var count = 0
        fun visit(dir: File, relative: String, depth: Int) {
            require(depth <= MAX_DEPTH) { "frozen library nesting is too deep" }
            val children = dir.listFiles() ?: error("cannot list frozen library directory")
            children.sortedBy { it.name }.forEach { child ->
                count++; require(count <= MAX_SOURCES + 1) { "too many frozen library entries" }
                require(!child.isSymbolicLinkCompat() && validName(child.name)) { "symbolic links or unsafe names are not allowed" }
                val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (child.isDirectory) visit(child, path, depth + 1) else {
                    require(child.isFile) { "only regular files and directories are allowed" }
                    val bytes = stableRead(child, if (path == MANIFEST_NAME) MAX_MANIFEST_BYTES else MAX_SOURCE_BYTES)
                    total += bytes.size; require(total <= MAX_LIBRARY_BYTES + MAX_MANIFEST_BYTES) { "frozen library exceeds the size limit" }
                    allFiles[path] = bytes
                }
            }
        }
        visit(root, "", 0)
        val manifestBytes = allFiles[MANIFEST_NAME] ?: error("frozen library manifest is missing")
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        require(manifestText.toByteArray(Charsets.UTF_8).contentEquals(manifestBytes)) { "frozen library manifest is not valid UTF-8" }
        val manifest = JSONObject(manifestText)
        require(manifest.keys().asSequence().toSet() == setOf("schema_version", "snapshot_id", "sources")) { "frozen library manifest has an invalid structure" }
        require(manifest.optString("schema_version") == "1.0") { "unsupported frozen library manifest version" }
        val manifestPin = manifest.optString("snapshot_id")
        require(manifestPin.matches(SNAPSHOT_PATTERN)) { "frozen library snapshot id is invalid" }
        if (pin.isNotBlank()) require(pin.matches(SNAPSHOT_PATTERN) && manifestPin == pin) { "frozen library snapshot id does not match the configured pin" }
        val sources = manifest.optJSONArray("sources") ?: error("frozen library sources are invalid")
        require(sources.length() in 1..MAX_SOURCES) { "frozen library must contain between 1 and $MAX_SOURCES sources" }
        val declared = mutableSetOf(MANIFEST_NAME); val ids = mutableSetOf<String>(); var sourceBytes = 0L
        val documents = mutableListOf<Document>()
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: error("frozen library source entry is invalid")
            require(item.keys().asSequence().toSet() == setOf("id", "title", "path", "sha256")) { "frozen library source entry has an invalid structure" }
            val id = item.getString("id"); val title = item.getString("title").trim(); val path = item.getString("path"); val hash = item.getString("sha256")
            require(id.matches(ID_PATTERN) && ids.add(id)) { "frozen library source ids must be unique uppercase identifiers" }
            require(title.isNotEmpty() && title.length <= 300 && validRelativeSourcePath(path) && declared.add(path)) { "frozen library source metadata is invalid" }
            require(hash.matches(HASH_PATTERN)) { "frozen library source hash is invalid" }
            val bytes = allFiles[path] ?: error("frozen library source is missing: $path")
            sourceBytes += bytes.size; require(sourceBytes <= MAX_LIBRARY_BYTES) { "frozen library exceeds the total source size limit" }
            require(JsonCanonical.sha256(bytes) == hash) { "frozen library source digest does not match its manifest" }
            val text = String(bytes, Charsets.UTF_8); require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "frozen library source is empty or not valid UTF-8" }
            documents += Document(SourceReference(id, title, path, hash), text)
        }
        require(allFiles.keys == declared) { "frozen library files do not exactly match the manifest" }
        val computed = "snapshot-${JsonCanonical.sha256(JsonCanonical.encode(sources).toByteArray(Charsets.UTF_8)).take(16)}"
        require(computed == manifestPin) { "frozen library snapshot id does not match its source metadata" }
        return computed to documents
    }

    private fun classifyFailure() = when {
        !root.isDirectory -> LibraryFailureReason.LIBRARY_UNAVAILABLE
        expectedSnapshotId.isBlank() -> if (runCatching { validateForPinRecovery() }.isSuccess) {
            LibraryFailureReason.LIBRARY_UNPINNED
        } else {
            LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE
        }
        !expectedSnapshotId.matches(SNAPSHOT_PATTERN) -> LibraryFailureReason.LIBRARY_UNAVAILABLE
        else -> LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        private const val STAGING_PREFIX = ".library-staging-"
        const val MAX_SOURCE_BYTES = 1024 * 1024; const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024; const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_SOURCES = 500; const val MAX_RESULTS = 5; const val MAX_EXCERPT_CHARS = 1600
        private const val MAX_DEPTH = 8
        private val SNAPSHOT_PATTERN = Regex("snapshot-[0-9a-f]{16}"); private val HASH_PATTERN = Regex("[0-9a-f]{64}"); private val ID_PATTERN = Regex("[A-Z0-9][A-Z0-9_-]{0,63}")
        private val stopWords = "a an and are as at be been but by can could did do does for from had has have he her here hers him his how i if in into is it its may me might more most my no not of on or our ours she should so than that the their them then there these they this those through to too up us was we were what when where which while who why will with would you your yours about according guide source sources cite citing cited citation citations tell explain describe summarize summary say says said need needs use used doesn't don't please local library document documents information".split(Regex("\\s+")).toSet()
        private val tokenPattern = Regex("[a-z0-9][a-z0-9_-]*")

        /** Prepare either a frozen manifest snapshot or a new source-only tree; never changes the picked tree. */
        fun prepareFromTree(context: Context, treeUri: Uri, expectedSnapshotId: String? = null, allowRepin: Boolean = false): Preparation {
            val copied = collectTreeFiles(context.contentResolver, treeUri)
            val hasManifest = MANIFEST_NAME in copied
            if (hasManifest && expectedSnapshotId == null) return prepareCopied(context, copied, null, false)
            if (hasManifest) error("freeze-new-sources refuses an existing manifest; use snapshot recovery")
            require(copied.isNotEmpty()) { "no source files selected" }
            val staging = createStaging(context); try {
                val sources = JSONArray(); val used = mutableSetOf<String>(); var total = 0L
                copied.toSortedMap().forEach { (path, bytes) ->
                    require(validRelativeSourcePath(path)) { "library may contain only .md and .txt sources" }
                    total += bytes.size; require(total <= MAX_LIBRARY_BYTES) { "library exceeds the 16 MiB source-size limit" }
                    val text = String(bytes, Charsets.UTF_8); require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes) && text.isNotBlank()) { "source is empty or not valid UTF-8: $path" }
                    val id = sourceId(path); require(id.matches(ID_PATTERN) && used.add(id)) { "source paths produce invalid or duplicate citation ids" }
                    val title = sourceTitle(text, path)
                    sources.put(JSONObject().put("id", id).put("title", title).put("path", path).put("sha256", JsonCanonical.sha256(bytes)))
                    writeExclusive(staging, path, bytes)
                }
                val pin = snapshotId(sources); require(expectedSnapshotId == null || expectedSnapshotId == pin || allowRepin) { "new library snapshot differs from the configured pin; explicit repin is required" }
                writeManifest(staging, pin, sources); makeReadOnlyTree(staging)
                return Preparation(staging, pin, sources.length())
            } catch (e: Exception) { deleteTree(staging); throw e }
        }

        /** Explicit API for recovery of an already frozen manifest snapshot. */
        fun prepareExistingSnapshot(context: Context, treeUri: Uri, expectedSnapshotId: String? = null, allowRepin: Boolean = false): Preparation {
            val copied = collectTreeFiles(context.contentResolver, treeUri); require(MANIFEST_NAME in copied) { "existing snapshot manifest is missing" }
            return prepareCopied(context, copied, expectedSnapshotId, allowRepin)
        }

        fun discardPreparation(context: Context, preparation: Preparation) {
            val staging = preparation.stagingDirectory
            require(staging.parentFile?.canonicalFile == context.filesDir.canonicalFile) {
                "refusing to remove a staging path outside app-private storage"
            }
            if (existsNoFollow(staging)) deleteTree(staging)
        }

        /** Removes only abandoned staging entries in this app's private files directory. */
        fun cleanupAbandonedPreparations(context: Context) = cleanupAbandonedPreparations(context.filesDir)

        internal fun cleanupAbandonedPreparations(privateFiles: File) {
            val privateRoot = privateFiles.canonicalFile
            val entries = privateRoot.listFiles() ?: error("cannot list app-private library staging directory")
            for (entry in entries) {
                if (!entry.name.startsWith(STAGING_PREFIX)) continue
                require(entry.parentFile?.canonicalFile == privateRoot) {
                    "refusing to clean a staging path outside app-private storage"
                }
                if (existsNoFollow(entry)) deleteTree(entry)
            }
        }

        fun installPrepared(
            context: Context,
            preparation: Preparation,
            settingsStore: SettingsStore,
            previousSettings: AppSettings,
            allowReplace: Boolean,
            allowRepin: Boolean,
        ): LibraryInfo {
            val privateRoot = context.filesDir
            val staging = preparation.stagingDirectory
            require(staging.parentFile?.canonicalFile == privateRoot.canonicalFile) { "staging directory is not app-private" }
            return installPreparedTree(
                staging = staging,
                current = File(privateRoot, "library"),
                privateRoot = privateRoot,
                snapshotId = preparation.snapshotId,
                previousPin = previousSettings.librarySnapshotId,
                allowReplace = allowReplace,
                allowRepin = allowRepin,
                persistPin = { pin -> settingsStore.updateLibrarySnapshotId(pin, previousSettings.librarySnapshotId) },
                restorePin = { settingsStore.restoreLibrarySnapshotId(previousSettings.librarySnapshotId, preparation.snapshotId) },
            )
        }

        internal fun installPreparedTree(
            staging: File,
            current: File,
            privateRoot: File,
            snapshotId: String,
            previousPin: String,
            allowReplace: Boolean,
            allowRepin: Boolean,
            persistPin: (String) -> Unit,
            restorePin: () -> Unit,
            atomicMove: (Path, Path) -> Unit = { source, target ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            },
        ): LibraryInfo {
            require(privateRoot.isDirectory && staging.parentFile?.canonicalFile == privateRoot.canonicalFile &&
                current.parentFile?.canonicalFile == privateRoot.canonicalFile) { "library transaction paths must share app-private storage" }
            val currentPresent = try {
                require(!staging.isSymbolicLinkCompat() && FrozenLibrary(staging, snapshotId).info().available) {
                    "staging snapshot failed final verification"
                }
                val present = existsNoFollow(current)
                require(!current.isSymbolicLinkCompat()) { "existing library path is a symbolic link; refusing replacement" }
                if (present) {
                    require(current.isDirectory) { "existing library path is not a directory" }
                    require(allowReplace) { "replacing an existing library requires explicit confirmation" }
                }
                val currentPin = if (present) runCatching { FrozenLibrary(current, "").validateForPinRecovery() }.getOrNull() else null
                val pinChanged = previousPin.isNotBlank() && previousPin != snapshotId
                val libraryChanged = currentPin != null && currentPin != snapshotId
                require((!pinChanged && !libraryChanged) || allowRepin) {
                    "changing the configured library snapshot requires explicit repin confirmation"
                }
                present
            } catch (failure: Exception) {
                cleanupStagingAfterFailure(staging, failure)
                throw failure
            }
            val backup = File(privateRoot, ".library-backup-${UUID.randomUUID()}")
            var previousMoved = false
            var newInstalled = false
            try {
                try {
                    if (currentPresent) {
                        moveToVacantSlot(current, backup, "could not preserve the previous library", atomicMove)
                        previousMoved = true
                    }
                    moveToVacantSlot(staging, current, "could not install the new Android library", atomicMove)
                    newInstalled = true
                    val installed = FrozenLibrary(current, snapshotId).info()
                    require(installed.available) { "installed library failed verification" }
                    persistPin(snapshotId)
                    if (existsNoFollow(backup)) {
                        try {
                            deleteTree(backup)
                        } catch (_: Exception) {
                            runCatching { makeReadOnlyTree(backup) }
                            return installed.copy(message = "Snapshot installed and pinned. The previous library backup remains in app-private storage as ${backup.name}.")
                        }
                    }
                    return installed
                } catch (failure: Exception) {
                    if (!previousMoved && currentPresent && existsNoFollow(backup)) {
                        if (existsNoFollow(current)) {
                            throw FrozenLibraryException(
                                LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE,
                                "atomic library move was ambiguous; the current path and recovery backup ${backup.name} are both preserved",
                                failure,
                            )
                        }
                        previousMoved = true
                    }
                    val settingsRollback = runCatching { restorePin() }.exceptionOrNull()
                    if (newInstalled && existsNoFollow(current)) {
                        try { deleteTree(current) } catch (_: Exception) {
                            throw FrozenLibraryException(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE,
                                "installation failed and the new tree could not be rolled back; the previous backup is preserved", failure)
                        }
                    }
                    if (previousMoved && existsNoFollow(backup)) {
                        val rollbackFailure = runCatching {
                            moveToVacantSlot(backup, current, "could not restore the previous library", atomicMove)
                        }.exceptionOrNull()
                        if (rollbackFailure != null) {
                            rollbackFailure.addSuppressed(failure)
                            throw FrozenLibraryException(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE,
                                "installation failed and rollback was uncertain; backup preserved at ${backup.name}", rollbackFailure)
                        }
                    }
                    if (settingsRollback != null) {
                        throw FrozenLibraryException(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE,
                            "library rollback completed, but restoring settings was uncertain; inspect the saved pin before running tasks", settingsRollback)
                    }
                    throw failure
                }
            } catch (failure: Exception) {
                cleanupStagingAfterFailure(staging, failure)
                throw failure
            }
        }

        /** Legacy activity entry point: pin is required and replacement is explicit. */
        fun importFromTree(context: Context, treeUri: Uri, expectedSnapshotId: String, replaceExisting: Boolean): LibraryInfo {
            val settingsStore = SettingsStore(context)
            val previousSettings = settingsStore.load()
            val prep = prepareExistingSnapshot(context, treeUri, expectedSnapshotId, allowRepin = replaceExisting)
            return installPrepared(context, prep, settingsStore, previousSettings,
                allowReplace = replaceExisting, allowRepin = replaceExisting)
        }

        private fun prepareCopied(context: Context, copied: Map<String, ByteArray>, expected: String?, allowRepin: Boolean): Preparation {
            val staging = createStaging(context); try { copied.forEach { (path, bytes) -> writeExclusive(staging, path, bytes) }; makeReadOnlyTree(staging)
                val pin = FrozenLibrary(staging, "").validateForPinRecovery(); require(expected == null || expected == pin || allowRepin) { "snapshot differs from the configured pin; explicit repin is required" }
                return Preparation(staging, pin, JSONObject(String(copied[MANIFEST_NAME]!!, Charsets.UTF_8)).getJSONArray("sources").length())
            } catch (e: Exception) { deleteTree(staging); throw e }
        }

        private data class SafChild(val id: String, val name: String, val mimeType: String, val size: Long, val modified: Long)

        private fun collectTreeFiles(resolver: ContentResolver, treeUri: Uri): Map<String, ByteArray> {
            require(treeUri.scheme == ContentResolver.SCHEME_CONTENT &&
                treeUri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(treeUri)) {
                "only an on-device external-storage folder is supported"
            }
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val all = linkedMapOf<String, ByteArray>()
            val files = linkedMapOf<String, SafChild>()
            val directories = linkedMapOf<String, List<SafChild>>()
            var entryCount = 0
            var totalBytes = 0L

            fun walk(parent: String, prefix: String, depth: Int) {
                require(depth <= MAX_DEPTH) { "selected library directory is nested too deeply" }
                val before = queryChildren(resolver, treeUri, parent)
                require(before.map { it.name }.distinct().size == before.size) { "selected library directory contains duplicate names" }
                directories[parent] = before
                for (child in before) {
                    entryCount++
                    require(entryCount <= MAX_SOURCES + 1) { "too many imported library entries" }
                    require(validName(child.name) && '\u0000' !in child.name) { "invalid selected filename" }
                    val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(child.id, path, depth + 1)
                    } else {
                        require(path == MANIFEST_NAME || validRelativeSourcePath(path)) {
                            "library may contain only manifest.json and .md/.txt sources"
                        }
                        require(path !in all) { "selected library contains duplicate file paths" }
                        val bytes = readSelectedDocument(resolver, treeUri, child, path)
                        totalBytes += bytes.size
                        require(totalBytes <= MAX_LIBRARY_BYTES + MAX_MANIFEST_BYTES) {
                            "selected library exceeds the 16 MiB source-size limit"
                        }
                        all[path] = bytes
                        files[path] = child
                    }
                }
                require(queryChildren(resolver, treeUri, parent) == before) {
                    "selected library directory changed while it was being read"
                }
            }

            walk(rootId, "", 0)
            // Re-read every included document and every directory listing after the initial walk.
            // If the picker/provider changed a file in place, its new bytes cannot be silently used.
            for ((path, entry) in files) {
                val after = readSelectedDocument(resolver, treeUri, entry, path)
                require(after.contentEquals(all.getValue(path))) { "selected file changed during import: $path" }
            }
            for ((directoryId, expected) in directories) {
                require(queryChildren(resolver, treeUri, directoryId) == expected) {
                    "selected library directory changed during import"
                }
            }
            return all
        }

        private fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentId: String): List<SafChild> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val result = mutableListOf<SafChild>()
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) result += cursor.toSafChild()
            } ?: error("cannot list selected library directory")
            return result.sortedWith(compareBy<SafChild> { it.name }.thenBy { it.id })
        }

        private fun queryDocument(resolver: ContentResolver, treeUri: Uri, documentId: String): SafChild {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                require(cursor.moveToFirst()) { "selected library document disappeared" }
                return cursor.toSafChild()
            } ?: error("cannot inspect selected library document")
        }

        private fun Cursor.toSafChild(): SafChild {
            val id = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val name = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                ?: error("selected entry has no name")
            val mime = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                ?: "application/octet-stream"
            val sizeColumn = getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            return SafChild(id, name, mime,
                if (sizeColumn >= 0 && !isNull(sizeColumn)) getLong(sizeColumn) else -1L,
                if (modifiedColumn >= 0 && !isNull(modifiedColumn)) getLong(modifiedColumn) else -1L)
        }

        private fun readSelectedDocument(resolver: ContentResolver, treeUri: Uri, entry: SafChild, path: String): ByteArray {
            require(queryDocument(resolver, treeUri, entry.id) == entry) { "selected file metadata changed: $path" }
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
            val maximum = if (path == MANIFEST_NAME) MAX_MANIFEST_BYTES else MAX_SOURCE_BYTES
            val pfd = resolver.openFileDescriptor(uri, "r") ?: error("cannot open selected file: $path")
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val before = Os.fstat(pfd.fileDescriptor)
                require(OsConstants.S_ISREG(before.st_mode)) { "selected library entry is not a regular file: $path" }
                require(before.st_size in 0..maximum.toLong() && (entry.size < 0 || entry.size == before.st_size)) {
                    "selected library file has an unsupported size: $path"
                }
                val bytes = readBounded(input, maximum)
                val after = Os.fstat(pfd.fileDescriptor)
                require(statSignature(before) == statSignature(after) && bytes.size.toLong() == before.st_size) {
                    "selected file changed while being read: $path"
                }
                require(queryDocument(resolver, treeUri, entry.id) == entry) { "selected file metadata changed: $path" }
                bytes
            }
        }

        private fun statSignature(stat: android.system.StructStat): List<Long> = listOf(
            stat.st_dev, stat.st_ino, stat.st_mode.toLong(), stat.st_size, stat.st_mtime, stat.st_ctime, stat.st_nlink,
        )
        private fun createStaging(c: Context): File { val f = File(c.filesDir, "$STAGING_PREFIX${UUID.randomUUID()}"); check(f.mkdir()) { "cannot create app-private staging directory" }; return f }
        private fun moveToVacantSlot(
            source: File,
            target: File,
            description: String,
            atomicMove: (Path, Path) -> Unit,
        ) {
            require(existsNoFollowChecked(source) && !existsNoFollowChecked(target)) {
                "$description: source or destination is in an unexpected state; preserving both paths"
            }
            val moveFailure = runCatching { atomicMove(source.toPath(), target.toPath()) }.exceptionOrNull()
            val sourceRemains = existsNoFollowChecked(source)
            val destinationExists = existsNoFollowChecked(target)
            if (!sourceRemains && destinationExists) return
            if (moveFailure != null) {
                throw IllegalStateException(
                    "$description: atomic move outcome is uncertain (source=$sourceRemains, destination=$destinationExists); preserving remaining paths",
                    moveFailure,
                )
            }
            throw IllegalStateException(
                "$description: atomic move did not reach a single destination (source=$sourceRemains, destination=$destinationExists); preserving remaining paths",
            )
        }
        private fun existsNoFollowChecked(f: File): Boolean = try {
            Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: java.nio.file.NoSuchFileException) { false }
        private fun writeExclusive(root: File, path: String, bytes: ByteArray) { val f = File(root, path); check(f.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "invalid staging path" }; f.parentFile?.mkdirs(); check(f.createNewFile()) { "staging file already exists" }; f.outputStream().use { it.write(bytes) } }
        private fun writeManifest(root: File, pin: String, sources: JSONArray) { val bytes = (JSONObject().put("schema_version", "1.0").put("snapshot_id", pin).put("sources", sources).toString() + "\n").toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_MANIFEST_BYTES); writeExclusive(root, MANIFEST_NAME, bytes) }
        private fun snapshotId(s: JSONArray) = "snapshot-${JsonCanonical.sha256(JsonCanonical.encode(s).toByteArray(Charsets.UTF_8)).take(16)}"
        private fun sourceId(path: String) = "DOC-${path.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "-").trim('-').take(60).trimEnd('-')}"
        private fun sourceTitle(text: String, path: String): String { val heading = text.lineSequence().map { it.trim() }.firstOrNull { it.matches(Regex("^#\\s+.+?\\s*#*\\s*$")) }; return (heading?.replace(Regex("^#\\s+"), "")?.replace(Regex("\\s*#+\\s*$"), "") ?: path.substringBeforeLast('.').substringAfterLast('/')).trim().take(300) }
        private fun validName(n: String) = n.isNotEmpty() && n != "." && n != ".." && '/' !in n && '\\' !in n
        private fun validRelativeSourcePath(p: String): Boolean { if (p.isEmpty() || p.startsWith('/') || '\\' in p) return false; val parts = p.split('/'); return parts.none { it.isEmpty() || it == "." || it == ".." } && parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "txt") }
        private fun existsNoFollow(f: File) = try { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); true } catch (_: Exception) { false }
        private fun File.isSymbolicLinkCompat() = Files.isSymbolicLink(toPath())
        private fun stableRead(f: File, max: Int): ByteArray {
            val path = f.toPath()
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.isRegularFile && !before.isSymbolicLink && f.canRead() && !f.canWrite()) {
                "frozen library files must be regular and genuinely read-only"
            }
            val output = ByteArrayOutputStream()
            FileChannel.open(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.allocate(8192)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= max) { "frozen library file exceeds its size limit" }
                    output.write(buffer.array(), 0, count)
                }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(after.isRegularFile && !after.isSymbolicLink && !f.canWrite() &&
                before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                before.fileKey() == after.fileKey() && output.size().toLong() == before.size()) {
                "frozen file changed while being read"
            }
            return output.toByteArray()
        }

        private fun requireTreeReadOnly(f: File) {
            val path = f.toPath()
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!before.isSymbolicLink && !f.canWrite() && (before.isDirectory || before.isRegularFile)) {
                "frozen library tree must contain only genuinely read-only regular files and directories"
            }
            if (before.isDirectory) {
                val children = f.listFiles()?.sortedBy { it.name } ?: error("cannot list frozen library directory")
                val childStamps = children.map { child -> child.name to Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }
                children.forEach(::requireTreeReadOnly)
                val afterChildren = f.listFiles()?.sortedBy { it.name } ?: error("cannot relist frozen library directory")
                val afterStamps = afterChildren.map { child -> child.name to Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(childStamps == afterStamps && before.fileKey() == after.fileKey() && !f.canWrite()) {
                    "frozen library directory changed during validation"
                }
            }
        }
        private fun makeReadOnlyTree(f: File) { if (f.isDirectory) f.listFiles()?.forEach(::makeReadOnlyTree); check(f.setWritable(false, false) && !f.canWrite()) { "staging entries could not be made read-only" } }
        private fun makeWritableTree(f: File) { if (f.isSymbolicLinkCompat()) return; if (f.isDirectory) f.listFiles()?.forEach(::makeWritableTree); f.setWritable(true, false) }
        private fun cleanupStagingAfterFailure(staging: File, failure: Exception) {
            if (!existsNoFollow(staging)) return
            try { deleteTree(staging) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        }
        private fun deleteTree(f: File) {
            if (!existsNoFollow(f)) return
            Files.walkFileTree(f.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, false)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink) file.toFile().setWritable(true, false)
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    dir.toFile().setWritable(true, false)
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        private fun excerpt(text: String, matched: Set<String>): String { val lines = mutableListOf<String>(); val seen = mutableSetOf<String>(); for (raw in text.lineSequence()) { val clean = raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" "); if (clean.isEmpty()) continue; val key = clean.lowercase(Locale.ROOT); if (key in seen || tokens(clean).none { it in matched }) continue; seen += key; lines += clean; if (lines.sumOf { it.length + 1 } >= MAX_EXCERPT_CHARS) break }; return if (lines.isEmpty()) text.replace(Regex("\\s+"), " ").take(MAX_EXCERPT_CHARS).trim() else lines.joinToString("\\n").take(MAX_EXCERPT_CHARS).trim() }
        private fun tokens(text: String) = tokenPattern.findAll(text.lowercase(Locale.ROOT)).map { it.value }.filter { it !in stopWords && it.length > 1 }.toList()
        private fun readBounded(input: InputStream, max: Int): ByteArray { val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= max) { "frozen library file exceeds its size limit" }; out.write(buffer, 0, n) }; return out.toByteArray() }
    }
}
