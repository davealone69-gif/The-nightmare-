package com.thenightmare.local

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.nio.channels.OverlappingFileLockException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Local-only portable project export. The authoritative folder/ZIP/manifest pair is built and
 * verified in app-private storage. SAF is used only to read a user-selected on-device project
 * folder; the app does not upload data. Sharing the ZIP and manifest is a separate, user-initiated
 * Android share-sheet action.
 */
object PortableProjectExporter {
    const val PORTABLE_NAME = "the-nightmare-portable"
    const val ARCHIVE_NAME = "$PORTABLE_NAME.zip"
    const val MANIFEST_NAME = "$PORTABLE_NAME.manifest.json"
    const val TRANSACTION_NAME = ".nightmare-export-transaction.json"
    const val STAGING_PREFIX = ".nightmare-export-"
    const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private const val FIXED_TIME_MILLIS = 315532800000L // 1980-01-01 00:00:00 UTC
    private const val MAX_FILES = 4096
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128 * 1024 * 1024
    private const val MAX_PATH_DEPTH = 64
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val DEFAULT_DATA_DIR = "../the-nightmare-data"
    private val requiredFiles = setOf("README.md", "requirements.txt", "config.json", "run.sh")
    private val excludedDirectoryNames = setOf(
        ".git", ".gradle", ".kotlin", "__pycache__", ".cache", ".pytest_cache", ".mypy_cache",
        ".ruff_cache", ".hypothesis", ".tox", ".nox", ".venv", "venv", "env", ".aws", ".ssh",
        ".gnupg", ".azure", ".kube", ".docker", "gcloud", "credentials", "secrets", "node_modules",
        "tests", "test", "dist", "build", "target", "logs", "log", "cache", "caches", "htmlcov",
        "test-results", "test_results", "test-artifacts", "test_artifacts",
    )
    private val excludedFileNames = setOf(".ds_store", ".coverage", "coverage.xml", "junit.xml", "nosetests.xml")
    private val sensitiveFileNames = setOf(
        ".npmrc", ".pypirc", ".netrc", ".git-credentials", ".dockercfg", "credentials", "credentials.json",
        "credentials.ini", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "id_ecdsa_sk", "id_ed25519_sk",
    )
    private val sensitiveNamePattern = Regex("(^|[._-])(credentials?|secrets?|tokens?)([._-]|$)")
    private val secretContentPatterns = listOf(
        Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
        Regex("-----BEGIN PGP (?:PRIVATE|SECRET) KEY BLOCK-----"),
        Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
        Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
        Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}\\b"),
    )
    private val secretFieldPattern = Regex(
        "(?im)(?:^|[{,])[ \\t]*(?:-[ \\t]*)?['\"]?([A-Za-z_][A-Za-z0-9_.-]*)['\"]?[ \\t]*[:=][ \\t]*[^\\r\\n,}]*",
    )
    private val shellExportPattern = Regex("(?im)(?:^|[;&])[ \\t]*export[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=")
    private val pythonEnvironmentAssignmentPattern = Regex("(?i)\\bos\\.environ\\s*\\[\\s*(['\"])([A-Za-z_][A-Za-z0-9_]*)\\1\\s*]\\s*=")
    private val secretFieldNames = setOf(
        "api_key", "access_key", "access_key_id", "access_token", "auth_token", "client_secret", "credential",
        "credentials", "password", "passwd", "private_key", "refresh_token", "secret", "secret_access_key",
        "secret_key", "token", "aws_access_key_id", "aws_secret_access_key", "aws_secret_key",
    )
    private val noWritePermissions = setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
    private val bundleFilePermissions = PosixFilePermissions.fromString("rw-r--r--")
    private val bundleExecutablePermissions = PosixFilePermissions.fromString("rwxr-xr-x")
    private val bundleDirectoryPermissions = PosixFilePermissions.fromString("rwxr-xr-x")
    private val readOnlyFilePermissions = PosixFilePermissions.fromString("r--r--r--")
    private val readOnlyDirectoryPermissions = PosixFilePermissions.fromString("r-xr-xr-x")
    private val binaryNameCharset: Charset = StandardCharsets.UTF_8
    private val byteTextCharset: Charset = Charset.forName("ISO-8859-1")

    data class PortableFile(val relativePath: String, val contents: ByteArray, val executable: Boolean)

    class ProjectSnapshot internal constructor(
        internal val files: List<PortableFile>,
        private val verifyUnchangedSource: () -> Unit,
    ) {
        internal fun verifySource() = verifyUnchangedSource()
    }

    data class ExportResult(
        val exportId: String,
        val directory: File,
        val folder: File,
        val archive: File,
        val manifest: File,
        val fileCount: Int,
        val treeSha256: String,
        val archiveSha256: String,
    )

    data class PairManifest(
        val treeSha256: String,
        val archiveSha256: String,
        val fileCount: Int,
    )

    private data class FileStamp(
        val path: String,
        val fileKey: String,
        val size: Long,
        val modifiedNanos: Long,
        val mode: Int,
        val sha256: String,
    )

    private data class DirectoryStamp(
        val path: String,
        val fileKey: String,
        val size: Long,
        val modifiedNanos: Long,
        val children: List<String>,
    )

    private data class LocalCapture(
        val root: Path,
        val rootKey: String,
        val files: List<PortableFile>,
        val fileStamps: List<FileStamp>,
        val directoryStamps: List<DirectoryStamp>,
        val runtimeDataPath: Path?,
    )

    private data class SafEntry(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
    )

    private data class SafDirectoryStamp(val id: String, val relativePath: String, val entries: List<SafEntry>)
    private data class SafFileStamp(val entry: SafEntry, val relativePath: String, val sha256: String, val bytes: Int, val executable: Boolean)
    private data class SafRead(val bytes: ByteArray, val executable: Boolean)
    private data class SafCapture(val treeUri: Uri, val files: List<PortableFile>, val directories: List<SafDirectoryStamp>, val fileStamps: List<SafFileStamp>)

    /** Capture a local filesystem tree for tests and app-private callers; never follows symlinks. */
    fun captureFromLocalDirectory(projectRoot: File): ProjectSnapshot {
        val capture = scanLocalTree(projectRoot.toPath())
        require(requiredFiles.all { required -> capture.files.any { it.relativePath == required } }) {
            "required project files missing from export: ${requiredFiles.filterNot { name -> capture.files.any { it.relativePath == name } }.sorted().joinToString(", ")}"
        }
        val copied = capture.files.map { it.copy(contents = it.contents.copyOf()) }
        return ProjectSnapshot(copied) { verifyLocalCapture(capture) }
    }

    /** Capture a user-selected tree only from Android's on-device external-storage provider. */
    fun captureFromLocalTree(context: Context, treeUri: Uri): ProjectSnapshot {
        require(treeUri.scheme == "content" && treeUri.authority == LOCAL_STORAGE_AUTHORITY && DocumentsContract.isTreeUri(treeUri)) {
            "portable export accepts only an on-device storage folder; cloud or unsupported providers are not read"
        }
        val resolver = context.contentResolver
        val capture = captureSafTree(resolver, treeUri)
        require(requiredFiles.all { required -> capture.files.any { it.relativePath == required } }) {
            "required project files are missing from the selected folder"
        }
        return ProjectSnapshot(capture.files.map { it.copy(contents = it.contents.copyOf()) }) {
            verifySafCapture(resolver, capture)
        }
    }

    fun export(snapshot: ProjectSnapshot, outputRoot: File): ExportResult {
        val root = outputRoot.toPath().toAbsolutePath().normalize()
        ensureSafePrivateOutputRoot(root)
        return withDestinationLock(root) {
            recoverMarkedExports(root)
            snapshot.verifySource()
            val stage = Files.createTempDirectory(root, STAGING_PREFIX)
            fsyncDirectory(root)
            var markerWritten = false
            try {
                val folder = stage.resolve(PORTABLE_NAME)
                writeBundle(snapshot.files, folder)
                val folderDigest = treeDigest(folder)
                val archive = stage.resolve(ARCHIVE_NAME)
                writeDeterministicZip(snapshot.files, archive)
                fsyncFile(archive)
                val archiveHash = sha256File(archive)
                fsyncDirectory(stage)

                // Recheck the selected source after the pair bytes are staged, before publication.
                snapshot.verifySource()
                val marker = transactionJson(folderDigest.sha256, archiveHash, folderDigest.fileCount)
                writeNewDurable(stage.resolve(TRANSACTION_NAME), JsonCanonical.encode(marker).plus("\n").toByteArray(StandardCharsets.UTF_8))
                markerWritten = true
                fsyncDirectory(stage)

                verifyComponents(stage, folderDigest.sha256, archiveHash, folderDigest.fileCount)
                val manifest = manifestJson(folderDigest.sha256, archiveHash, folderDigest.fileCount)
                writeNewDurable(stage.resolve(MANIFEST_NAME), JsonCanonical.encode(manifest).plus("\n").toByteArray(StandardCharsets.UTF_8))
                fsyncDirectory(stage)
                Files.deleteIfExists(stage.resolve(TRANSACTION_NAME))
                fsyncDirectory(stage)
                fsyncDirectory(root)
                val verified = verifyExportPair(stage.toFile())
                check(verified.treeSha256 == folderDigest.sha256 && verified.archiveSha256 == archiveHash) {
                    "published export pair did not match its completion manifest"
                }
                ExportResult(
                    exportId = stage.fileName.toString(),
                    directory = stage.toFile(),
                    folder = folder.toFile(),
                    archive = archive.toFile(),
                    manifest = stage.resolve(MANIFEST_NAME).toFile(),
                    fileCount = folderDigest.fileCount,
                    treeSha256 = folderDigest.sha256,
                    archiveSha256 = archiveHash,
                )
            } catch (exception: Exception) {
                // Keep any partial output for inspection. It has no completion manifest and cannot
                // be shared through PortableExportProvider. A later run uses a fresh unique dir.
                if (!markerWritten) runCatching { fsyncDirectory(stage) }
                throw exception
            }
        }
    }

    fun export(projectRoot: File, outputRoot: File): ExportResult = export(captureFromLocalDirectory(projectRoot), outputRoot)

    /** Verify the manifest-gated folder/archive pair. No output is accepted without the manifest. */
    fun verifyExportPair(outputDirectory: File): PairManifest {
        val root = outputDirectory.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "portable export directory is not a regular directory"
        }
        val manifestPath = root.resolve(MANIFEST_NAME)
        require(!Files.isSymbolicLink(manifestPath) && Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            "portable export pair is incomplete: completion manifest is missing or unsafe"
        }
        val manifestBytes = readFileNoFollow(manifestPath, MAX_MANIFEST_BYTES)
        val manifestText = decodeUtf8(manifestBytes, "portable export manifest")
        val manifest = try { JSONObject(manifestText) } catch (exception: JSONException) {
            throw IllegalArgumentException("portable export completion manifest is invalid", exception)
        }
        require(manifest.keys().asSequence().toSet() == setOf("version", "folder", "archive", "tree_sha256", "archive_sha256", "file_count") &&
            manifest.optInt("version", -1) == 1 && manifest.optString("folder") == PORTABLE_NAME && manifest.optString("archive") == ARCHIVE_NAME) {
            "portable export completion manifest has an invalid structure"
        }
        val treeHash = manifest.optString("tree_sha256")
        val archiveHash = manifest.optString("archive_sha256")
        val countValue = manifest.opt("file_count")
        require(treeHash.matches(Regex("[0-9a-f]{64}")) && archiveHash.matches(Regex("[0-9a-f]{64}")) &&
            countValue is Number && countValue.toDouble() == countValue.toInt().toDouble() && countValue.toInt() > 0) {
            "portable export completion manifest contains invalid hashes or file count"
        }
        verifyComponents(root, treeHash, archiveHash, countValue.toInt())
        return PairManifest(treeHash, archiveHash, countValue.toInt())
    }

    fun findVerifiedExports(outputRoot: File): List<ExportResult> {
        val root = outputRoot.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return emptyList()
        return Files.newDirectoryStream(root).use { entries ->
            entries.mapNotNull { entry ->
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) return@mapNotNull null
                try {
                    val manifest = verifyExportPair(entry.toFile())
                    ExportResult(entry.fileName.toString(), entry.toFile(), entry.resolve(PORTABLE_NAME).toFile(),
                        entry.resolve(ARCHIVE_NAME).toFile(), entry.resolve(MANIFEST_NAME).toFile(), manifest.fileCount,
                        manifest.treeSha256, manifest.archiveSha256)
                } catch (_: Exception) { null }
            }.sortedByDescending { it.directory.lastModified() }
        }
    }

    private fun scanLocalTree(rootInput: Path): LocalCapture {
        val root = rootInput.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "project root must be a regular directory, not a symbolic link"
        }
        val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val rootKey = fileKey(rootAttributes)
        val runtimeDataPath = configuredRuntimeDataPath(root)
        val files = mutableListOf<PortableFile>()
        val fileStamps = mutableListOf<FileStamp>()
        val directoryStamps = mutableListOf<DirectoryStamp>()
        var totalBytes = 0L
        var itemCount = 0

        fun walk(directory: Path, relativeDir: String, depth: Int) {
            require(depth <= MAX_PATH_DEPTH) { "project directory nesting is too deep" }
            val before = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.isDirectory && !before.isSymbolicLink) { "project directory changed during export" }
            val children = Files.newDirectoryStream(directory).use { stream -> stream.toList().sortedBy { it.fileName.toString() } }
            val childSignatures = children.map { child -> child.fileName.toString() + "\u0000" + entryIdentity(child) }
            directoryStamps += DirectoryStamp(relativeDir, fileKey(before), before.size(), before.lastModifiedTime().toMillis() * 1_000_000L, childSignatures)
            for (child in children) {
                itemCount += 1
                require(itemCount <= MAX_FILES * 4) { "project contains too many entries" }
                val name = child.fileName.toString()
                val relative = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                val attrs = try { Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                    catch (exception: Exception) { throw IllegalArgumentException("source path changed during export: $relative", exception) }
                if (attrs.isSymbolicLink) continue
                if (runtimeDataPath != null && child.toAbsolutePath().normalize().startsWith(runtimeDataPath)) continue
                if (attrs.isDirectory) {
                    if (name.lowercase(Locale.ROOT) in excludedDirectoryNames || sensitiveNamePattern.containsMatchIn(name.lowercase(Locale.ROOT))) continue
                    walk(child, relative, depth + 1)
                    continue
                }
                if (!attrs.isRegularFile || isExcludedFile(name)) continue
                val bytes = readFileNoFollow(child, MAX_FILE_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_BYTES) { "project exceeds the Android portable-export size limit" }
                if (containsSecretMaterial(bytes)) throw IllegalArgumentException("potential secret material in project file; export aborted: $relative")
                val after = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(sameFileSignature(attrs, after) && bytes.size.toLong() == attrs.size()) {
                    "source content or path changed during export: $relative"
                }
                val executable = try {
                    executableModeBits(Files.getPosixFilePermissions(child, LinkOption.NOFOLLOW_LINKS)) != 0
                } catch (exception: Exception) {
                    throw IllegalStateException("source executable mode cannot be inspected safely: $relative", exception)
                }
                files += PortableFile(relative, bytes, executable)
                fileStamps += FileStamp(relative, fileKey(attrs), attrs.size(), attrs.lastModifiedTime().toMillis() * 1_000_000L,
                    attrsHashMode(child), sha256Hex(bytes))
                require(files.size <= MAX_FILES) { "project contains too many files" }
            }
            val afterChildren = Files.newDirectoryStream(directory).use { stream -> stream.toList().sortedBy { it.fileName.toString() } }
            require(childSignatures == afterChildren.map { it.fileName.toString() + "\u0000" + entryIdentity(it) }) {
                "source directory contents changed during export: ${relativeDir.ifEmpty { "." }}"
            }
            val after = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(fileKey(before) == fileKey(after) && before.lastModifiedTime() == after.lastModifiedTime()) {
                "source directory changed during export: ${relativeDir.ifEmpty { "." }}"
            }
        }

        walk(root, "", 0)
        val sortedFiles = files.sortedBy { it.relativePath }
        val found = sortedFiles.map { it.relativePath }.toSet()
        val missing = requiredFiles - found
        require(missing.isEmpty()) { "required project file(s) missing from export: ${missing.sorted().joinToString(", ")}" }
        return LocalCapture(root, rootKey, sortedFiles, fileStamps.sortedBy { it.path }, directoryStamps.sortedBy { it.path }, runtimeDataPath)
    }

    private fun verifyLocalCapture(capture: LocalCapture) {
        val fresh = scanLocalTree(capture.root)
        require(fresh.rootKey == capture.rootKey && fresh.files.map { it.relativePath to sha256Hex(it.contents) } ==
            capture.files.map { it.relativePath to sha256Hex(it.contents) } && fresh.fileStamps == capture.fileStamps && fresh.directoryStamps == capture.directoryStamps) {
            "project source changed during export"
        }
    }

    private fun captureSafTree(resolver: ContentResolver, treeUri: Uri): SafCapture {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val files = mutableListOf<PortableFile>()
        val directories = mutableListOf<SafDirectoryStamp>()
        val fileStamps = mutableListOf<SafFileStamp>()
        var total = 0L
        var entryCount = 0
        val rootEntries = querySafChildren(resolver, treeUri, rootId)
        val config = rootEntries.firstOrNull { it.name == "config.json" && it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
        val configBytes = config?.let { readSafRegularFile(resolver, treeUri, it, "config.json", MAX_FILE_BYTES).bytes }
        val runtimeDataPath = configuredRuntimeDataRelative(configBytes)

        fun walk(parentId: String, relativeDir: String, depth: Int, initialEntries: List<SafEntry>? = null) {
            require(depth <= MAX_PATH_DEPTH) { "selected project folder nesting is too deep" }
            val entries = initialEntries ?: querySafChildren(resolver, treeUri, parentId)
            require(entries.map { it.name }.distinct().size == entries.size) { "selected folder contains duplicate names" }
            directories += SafDirectoryStamp(parentId, relativeDir, entries)
            for (entry in entries) {
                entryCount += 1
                require(entryCount <= MAX_FILES * 4) { "selected project folder contains too many entries" }
                require(entry.name !in setOf("", ".", "..") && '/' !in entry.name && '\\' !in entry.name && '\u0000' !in entry.name) {
                    "selected project folder contains an unsafe filename"
                }
                val relative = if (relativeDir.isEmpty()) entry.name else "$relativeDir/${entry.name}"
                if (runtimeDataPath != null && (relative == runtimeDataPath || relative.startsWith("$runtimeDataPath/"))) continue
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (lower in excludedDirectoryNames || sensitiveNamePattern.containsMatchIn(lower)) continue
                    walk(entry.id, relative, depth + 1)
                    continue
                }
                if (isExcludedFile(entry.name)) continue
                val read = readSafRegularFile(resolver, treeUri, entry, relative)
                val bytes = read.bytes
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "selected project exceeds the Android portable-export size limit" }
                if (containsSecretMaterial(bytes)) throw IllegalArgumentException("potential secret material in project file; export aborted: $relative")
                files += PortableFile(relative, bytes, read.executable)
                fileStamps += SafFileStamp(entry, relative, sha256Hex(bytes), bytes.size, read.executable)
                require(files.size <= MAX_FILES) { "selected project contains too many files" }
            }
            require(querySafChildren(resolver, treeUri, parentId) == entries) {
                "selected project directory changed while it was being read: ${relativeDir.ifEmpty { "." }}"
            }
        }
        walk(rootId, "", 0, rootEntries)
        for (file in fileStamps) {
            val read = readSafRegularFile(resolver, treeUri, querySafDocument(resolver, treeUri, file.entry.id), file.relativePath)
            require(read.bytes.size == file.bytes && sha256Hex(read.bytes) == file.sha256 && read.executable == file.executable) {
                "selected project file changed during export: ${file.relativePath}"
            }
        }
        for (directory in directories) {
            require(querySafChildren(resolver, treeUri, directory.id) == directory.entries) {
                "selected project directory changed during export: ${directory.relativePath.ifEmpty { "." }}"
            }
        }
        val sorted = files.sortedBy { it.relativePath }
        val found = sorted.map { it.relativePath }.toSet()
        val missing = requiredFiles - found
        require(missing.isEmpty()) { "required project files are missing from the selected folder: ${missing.sorted().joinToString(", ")}" }
        return SafCapture(treeUri, sorted, directories, fileStamps)
    }

    private fun verifySafCapture(resolver: ContentResolver, capture: SafCapture) {
        require(capture.treeUri.authority == LOCAL_STORAGE_AUTHORITY) { "selected project provider is not local storage" }
        for (directory in capture.directories) {
            val current = querySafChildren(resolver, capture.treeUri, directory.id)
            require(current == directory.entries) { "selected project folder changed during export: ${directory.relativePath.ifEmpty { "." }}" }
        }
        for (file in capture.fileStamps) {
            val current = querySafDocument(resolver, capture.treeUri, file.entry.id)
            require(current == file.entry) { "selected project file changed during export: ${file.relativePath}" }
            val read = readSafRegularFile(resolver, capture.treeUri, current, file.relativePath)
            require(read.bytes.size == file.bytes && sha256Hex(read.bytes) == file.sha256 && read.executable == file.executable) {
                "selected project content changed during export: ${file.relativePath}"
            }
            require(querySafDocument(resolver, capture.treeUri, file.entry.id) == file.entry) {
                "selected project file changed during export: ${file.relativePath}"
            }
        }
        for (directory in capture.directories) {
            require(querySafChildren(resolver, capture.treeUri, directory.id) == directory.entries) {
                "selected project directory changed during export: ${directory.relativePath.ifEmpty { "." }}"
            }
        }
    }

    private fun querySafChildren(resolver: ContentResolver, treeUri: Uri, parentId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<SafEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.readSafEntry()
        } ?: error("cannot list selected project directory")
        return result.sortedWith(compareBy<SafEntry> { it.name }.thenBy { it.id })
    }

    private fun querySafDocument(resolver: ContentResolver, treeUri: Uri, documentId: String): SafEntry {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "selected project file disappeared" }
            return cursor.readSafEntry()
        } ?: error("cannot inspect selected project file")
    }

    private fun Cursor.readSafEntry(): SafEntry {
        val id = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val name = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: error("selected project item has no name")
        val mime = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: "application/octet-stream"
        val sizeIndex = getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return SafEntry(id, name, mime, if (sizeIndex >= 0 && !isNull(sizeIndex)) getLong(sizeIndex) else -1L,
            if (modifiedIndex >= 0 && !isNull(modifiedIndex)) getLong(modifiedIndex) else -1L)
    }

    private fun readSafRegularFile(resolver: ContentResolver, treeUri: Uri, entry: SafEntry, relative: String, maxBytes: Int = MAX_FILE_BYTES): SafRead {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
        val pfd = resolver.openFileDescriptor(uri, "r") ?: error("cannot read selected project file: $relative")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return stream.use { input ->
            val before = Os.fstat(pfd.fileDescriptor)
            require(OsConstants.S_ISREG(before.st_mode)) { "selected project entry is not a regular file: $relative" }
            require(before.st_size <= maxBytes && (entry.size < 0 || entry.size == before.st_size)) {
                "selected project file has an unsupported size: $relative"
            }
            val bytes = readBounded(input, maxBytes)
            val after = Os.fstat(pfd.fileDescriptor)
            require(statSignature(before) == statSignature(after) && bytes.size.toLong() == before.st_size) {
                "selected project file changed while it was being read: $relative"
            }
            SafRead(bytes, before.st_mode and 0x49 != 0)
        }
    }

    private fun statSignature(value: android.system.StructStat): List<Long> = listOf(
        value.st_dev, value.st_ino, value.st_mode.toLong(), value.st_size, value.st_mtime, value.st_ctime, value.st_nlink,
    )

    private fun isExcludedFile(rawName: String): Boolean {
        val name = rawName.lowercase(Locale.ROOT)
        val suffix = name.substringAfterLast('.', "").let { if ('.' in name) ".$it" else "" }
        if (name in excludedFileNames || name in sensitiveFileNames) return true
        if (sensitiveNamePattern.containsMatchIn(name) && suffix !in setOf(".py", ".md", ".rst")) return true
        if (name.startsWith(".env")) return true
        if (suffix in setOf(".pyc", ".pyo", ".log", ".tmp", ".swp")) return true
        if (name.startsWith("test_") && suffix == ".py") return true
        if (name.endsWith("_test.py")) return true
        if (name.endsWith(".log.1") || ".log." in name) return true
        if (suffix in setOf(".pem", ".key", ".p12", ".pfx")) return true
        return false
    }

    private fun containsSecretMaterial(bytes: ByteArray): Boolean {
        val text = String(bytes, byteTextCharset)
        if (secretContentPatterns.any { it.containsMatchIn(text) }) return true
        val fieldNames = mutableListOf<String>()
        secretFieldPattern.findAll(text).forEach { fieldNames += it.groupValues[1] }
        shellExportPattern.findAll(text).forEach { fieldNames += it.groupValues[1] }
        pythonEnvironmentAssignmentPattern.findAll(text).forEach { fieldNames += it.groupValues[2] }
        for (rawName in fieldNames) {
            var name = rawName
            if (name == name.uppercase(Locale.ROOT) && name.endsWith("_PATTERN") ||
                name == name.uppercase(Locale.ROOT) && name.endsWith("_PATTERNS") ||
                name == name.uppercase(Locale.ROOT) && name.endsWith("_FIELD_NAMES")) continue
            val camelSeparated = Regex("([a-z0-9])([A-Z])").replace(name) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            val normalized = Regex("[^a-z0-9]+").replace(camelSeparated.lowercase(Locale.ROOT), "_").trim('_')
            val components = normalized.split('_').toSet()
            val compact = normalized.replace("[^a-z0-9]".toRegex(), "")
            if (components.intersect(setOf("pattern", "regex", "regexp")).isNotEmpty() && "token" in components &&
                components.intersect(setOf("access", "api", "credential", "credentials", "password", "passwd", "private", "secret")).isEmpty()) continue
            if (normalized in secretFieldNames) return true
            if (components.intersect(setOf("credential", "credentials", "password", "passwd", "secret", "token")).isNotEmpty()) return true
            if ("key" in components && components.intersect(setOf("access", "api", "private", "secret")).isNotEmpty()) return true
            if (compact.startsWith("aws") && listOf("secret", "accesskey", "credential", "sessiontoken").any { it in compact }) return true
            if (listOf("secret", "password", "passwd", "credential").any { it in compact }) return true
        }
        return false
    }

    private fun configuredRuntimeDataPath(root: Path): Path? {
        val config = root.resolve("config.json")
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(config)) return null
        val relative = configuredRuntimeDataRelative(readFileNoFollow(config, MAX_FILE_BYTES)) ?: return null
        val candidate = root.resolve(relative).normalize()
        require(candidate != root && candidate.startsWith(root)) {
            "project config data_dir does not identify a safe directory below the project root"
        }
        return candidate
    }

    internal fun configuredRuntimeDataRelative(configBytes: ByteArray?): String? {
        if (configBytes == null) return null
        val config = try { JSONObject(decodeUtf8(configBytes, "project config")) } catch (exception: Exception) {
            throw IllegalArgumentException("project config could not be parsed for runtime-data exclusion", exception)
        }
        if (!config.has("data_dir")) return null
        val value = config.opt("data_dir") as? String
            ?: throw IllegalArgumentException("project config data_dir must be a relative path for safe export")
        require(value.isNotBlank() && !value.startsWith('/') &&
            !Regex("^[A-Za-z]:[\\\\/].*").containsMatchIn(value)) {
            "absolute or empty data_dir cannot be excluded safely from a selected folder"
        }
        val parts = mutableListOf<String>()
        var escapesRoot = false
        for (component in value.replace('\\', '/').split('/')) {
            when (component) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) escapesRoot = true else parts.removeAt(parts.lastIndex)
                else -> parts += component
            }
        }
        if (escapesRoot) return null
        require(parts.isNotEmpty()) { "project config data_dir points at the project root; export stopped" }
        return parts.joinToString("/")
    }

    private data class TreeDigest(val sha256: String, val fileCount: Int, val executableModeSha256: String)

    private fun treeDigest(root: Path): TreeDigest {
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "portable folder is not a regular directory" }
        val files = mutableListOf<Triple<String, ByteArray, Int>>()
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && attrs.isDirectory) { "portable folder contains an unsafe directory" }
                if (dir != root) require(!Files.isSymbolicLink(dir)) { "portable folder contains a symbolic link" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).joinToString("/") { it.toString() }
                require(!attrs.isSymbolicLink && attrs.isRegularFile && !Files.isSymbolicLink(file)) {
                    "portable folder contains a symlink or non-regular entry: $relative"
                }
                val bytes = readFileNoFollow(file, MAX_FILE_BYTES)
                val executableMode = executableModeBits(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
                files += Triple(relative, MessageDigest.getInstance("SHA-256").digest(bytes), executableMode)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                throw IllegalArgumentException("portable folder changed or contains an unsafe entry", exc)
            }
        }
        Files.walkFileTree(root, visitor)
        val digest = MessageDigest.getInstance("SHA-256")
        val modeDigest = MessageDigest.getInstance("SHA-256")
        for ((name, contentHash, executableMode) in files.sortedBy { it.first }) {
            val encoded = encodeUtf8(name, "portable path")
            digest.update(longBytes(encoded.size.toLong()))
            digest.update(encoded)
            digest.update(contentHash)
            updateExecutableModeDigest(modeDigest, encoded, executableMode)
        }
        return TreeDigest(hex(digest.digest()), files.size, hex(modeDigest.digest()))
    }

    private fun zipTreeDigest(archivePath: Path): TreeDigest {
        val modes = zipEntryModes(archivePath)
        val names = mutableSetOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val modeDigest = MessageDigest.getInstance("SHA-256")
        var count = 0
        try {
            ZipFile(archivePath.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList().sortedBy { it.name }
                for (entry in entries) {
                    val name = entry.name
                    require(!entry.isDirectory && !name.startsWith('/') && '\\' !in name &&
                        name.split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(name)) {
                        "portable ZIP contains an unsafe or duplicate entry"
                    }
                    val mode = modes[name] ?: error("portable ZIP central directory is invalid")
                    val type = mode and 0xF000
                    require(type == 0 || type == 0x8000) { "portable ZIP contains a non-regular entry" }
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_FILE_BYTES) }
                    val encoded = encodeUtf8(name, "ZIP path")
                    digest.update(longBytes(encoded.size.toLong()))
                    digest.update(encoded)
                    digest.update(MessageDigest.getInstance("SHA-256").digest(bytes))
                    updateExecutableModeDigest(modeDigest, encoded, mode and 0x49)
                    count += 1
                    require(count <= MAX_FILES) { "portable ZIP contains too many files" }
                }
            }
        } catch (exception: Exception) {
            if (exception is IllegalArgumentException) throw exception
            throw IllegalArgumentException("portable ZIP is incomplete or invalid", exception)
        }
        return TreeDigest(hex(digest.digest()), count, hex(modeDigest.digest()))
    }

    private fun verifyComponents(root: Path, expectedTreeHash: String, expectedArchiveHash: String, expectedCount: Int) {
        val folder = root.resolve(PORTABLE_NAME)
        val archive = root.resolve(ARCHIVE_NAME)
        require(!Files.isSymbolicLink(folder) && !Files.isSymbolicLink(archive) &&
            Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            "portable export pair is incomplete or unsafe"
        }
        val folderDigest = treeDigest(folder)
        val zipDigest = zipTreeDigest(archive)
        require(sha256File(archive) == expectedArchiveHash && folderDigest.sha256 == expectedTreeHash &&
            zipDigest.sha256 == expectedTreeHash && folderDigest.fileCount == expectedCount && zipDigest.fileCount == expectedCount &&
            folderDigest.executableModeSha256 == zipDigest.executableModeSha256) {
            "portable export folder and ZIP do not form the manifested pair"
        }
    }

    private fun manifestJson(treeHash: String, archiveHash: String, fileCount: Int): JSONObject = JSONObject()
        .put("version", 1).put("folder", PORTABLE_NAME).put("archive", ARCHIVE_NAME)
        .put("tree_sha256", treeHash).put("archive_sha256", archiveHash).put("file_count", fileCount)

    private fun transactionJson(treeHash: String, archiveHash: String, fileCount: Int): JSONObject = JSONObject()
        .put("version", 1).put("tree_sha256", treeHash).put("archive_sha256", archiveHash).put("file_count", fileCount)

    private fun recoverMarkedExports(outputRoot: Path) {
        Files.newDirectoryStream(outputRoot).use { stream ->
            for (entry in stream) {
                if (!entry.fileName.toString().startsWith(STAGING_PREFIX) || Files.isSymbolicLink(entry) ||
                    !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
                val marker = entry.resolve(TRANSACTION_NAME)
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) continue
                require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    "portable export recovery found an unsafe transaction marker; preserving output"
                }
                val data = try { JSONObject(decodeUtf8(readFileNoFollow(marker, MAX_MANIFEST_BYTES), "export transaction marker")) }
                    catch (exception: Exception) { throw IllegalStateException("portable export transaction marker is invalid; preserving output", exception) }
                val treeHash = data.optString("tree_sha256")
                val archiveHash = data.optString("archive_sha256")
                val count = data.optInt("file_count", -1)
                require(treeHash.matches(Regex("[0-9a-f]{64}")) && archiveHash.matches(Regex("[0-9a-f]{64}")) && count > 0) {
                    "portable export transaction marker is invalid; preserving output"
                }
                verifyComponents(entry, treeHash, archiveHash, count)
                val manifest = entry.resolve(MANIFEST_NAME)
                if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    val checked = verifyExportPair(entry.toFile())
                    require(checked.treeSha256 == treeHash && checked.archiveSha256 == archiveHash && checked.fileCount == count) {
                        "portable export recovery found a mismatched completion manifest; preserving output"
                    }
                } else {
                    writeNewDurable(manifest, JsonCanonical.encode(manifestJson(treeHash, archiveHash, count)).plus("\n").toByteArray(StandardCharsets.UTF_8))
                    fsyncDirectory(entry)
                }
                Files.delete(marker)
                fsyncDirectory(entry)
                fsyncDirectory(outputRoot)
            }
        }
    }

    private fun writeBundle(files: List<PortableFile>, destination: Path) {
        Files.createDirectory(destination)
        setPermissions(destination, bundleDirectoryPermissions)
        Files.setLastModifiedTime(destination, FileTime.fromMillis(FIXED_TIME_MILLIS))
        for (source in files.sortedBy { it.relativePath }) {
            validateRelativePath(source.relativePath)
            val target = source.relativePath.split('/').fold(destination) { current, component -> current.resolve(component) }
            var parent = target.parent
            val missingParents = mutableListOf<Path>()
            while (parent != destination && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                missingParents.add(parent)
                parent = parent.parent
            }
            for (dir in missingParents.asReversed()) {
                Files.createDirectory(dir)
                setPermissions(dir, bundleDirectoryPermissions)
                Files.setLastModifiedTime(dir, FileTime.fromMillis(FIXED_TIME_MILLIS))
            }
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "duplicate portable output path" }
            writeNewDurable(target, source.contents)
            setPermissions(target, if (source.executable) bundleExecutablePermissions else bundleFilePermissions)
            Files.setLastModifiedTime(target, FileTime.fromMillis(FIXED_TIME_MILLIS))
        }
        val directories = mutableListOf<Path>()
        Files.walkFileTree(destination, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                directories.add(dir)
                return FileVisitResult.CONTINUE
            }
        })
        directories.sortedByDescending { it.nameCount }.forEach { directory ->
            Files.setLastModifiedTime(directory, FileTime.fromMillis(FIXED_TIME_MILLIS))
        }
        fsyncTree(destination)
    }

    private fun writeDeterministicZip(files: List<PortableFile>, archive: Path) {
        FileOutputStream(archive.toFile(), false).use { fileOutput ->
            ZipOutputStream(fileOutput, StandardCharsets.UTF_8).use { zip ->
                zip.setLevel(9)
                for (file in files.sortedBy { it.relativePath }) {
                    validateRelativePath(file.relativePath)
                    val entry = ZipEntry(file.relativePath).apply {
                        time = FIXED_TIME_MILLIS
                        method = ZipEntry.DEFLATED
                        extra = null
                        comment = null
                    }
                    zip.putNextEntry(entry)
                    zip.write(file.contents)
                    zip.closeEntry()
                }
            }
        }
        patchZipUnixModes(archive, files.associate { it.relativePath to if (it.executable) 0x1ED else 0x1A4 })
    }

    private fun patchZipUnixModes(path: Path, modes: Map<String, Int>) {
        RandomAccessFile(path.toFile(), "rw").use { file ->
            val length = file.length()
            var offset = 0L
            val patched = mutableSetOf<String>()
            while (offset + 46 <= length) {
                file.seek(offset)
                if (readIntLittleEndian(file) != 0x02014b50) { offset += 1; continue }
                val fixed = ByteArray(42)
                file.readFully(fixed)
                val nameLength = unsignedShortLittleEndian(fixed, 24)
                val extraLength = unsignedShortLittleEndian(fixed, 26)
                val commentLength = unsignedShortLittleEndian(fixed, 28)
                require(offset + 46L + nameLength + extraLength + commentLength <= length) { "portable ZIP central directory is truncated" }
                val nameBytes = ByteArray(nameLength)
                file.readFully(nameBytes)
                val name = decodeUtf8(nameBytes, "ZIP entry name")
                val mode = modes[name] ?: error("portable ZIP central directory contains an unexpected file")
                file.seek(offset + 4)
                writeShortLittleEndian(file, 0x0314) // Unix host, ZIP version 2.0
                file.seek(offset + 38)
                writeIntLittleEndian(file, ((0x8000 or mode) shl 16)) // regular file with POSIX mode
                patched += name
                offset += 46L + nameLength + extraLength + commentLength
            }
            require(patched == modes.keys) { "portable ZIP central directory is incomplete" }
            file.fd.sync()
        }
    }

    private fun zipEntryModes(path: Path): Map<String, Int> {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val length = file.length()
            val scanSize = minOf(length, 65_557L).toInt()
            require(scanSize >= 22) { "portable ZIP is truncated" }
            val tail = ByteArray(scanSize)
            file.seek(length - scanSize)
            file.readFully(tail)
            var eocd = -1
            for (index in tail.size - 22 downTo 0) {
                if (readIntLittleEndian(tail, index) == 0x06054b50) { eocd = index; break }
            }
            require(eocd >= 0) { "portable ZIP end directory is missing" }
            val entries = unsignedShortLittleEndian(tail, eocd + 10)
            val centralOffset = unsignedIntLittleEndian(tail, eocd + 16)
            require(entries in 1..MAX_FILES && centralOffset < length) { "portable ZIP central directory is invalid" }
            val result = linkedMapOf<String, Int>()
            var offset = centralOffset
            repeat(entries) {
                file.seek(offset)
                require(readIntLittleEndian(file) == 0x02014b50) { "portable ZIP central directory is invalid" }
                val fixed = ByteArray(42)
                file.readFully(fixed)
                val nameLength = unsignedShortLittleEndian(fixed, 24)
                val extraLength = unsignedShortLittleEndian(fixed, 26)
                val commentLength = unsignedShortLittleEndian(fixed, 28)
                val mode = (unsignedIntLittleEndian(fixed, 34) ushr 16).toInt()
                val nameBytes = ByteArray(nameLength)
                file.readFully(nameBytes)
                val name = decodeUtf8(nameBytes, "ZIP entry name")
                require(result.put(name, mode) == null) { "portable ZIP contains duplicate entries" }
                offset += 46L + nameLength + extraLength + commentLength
                require(offset <= length) { "portable ZIP central directory is truncated" }
            }
            return result
        }
    }

    private fun writeNewDurable(path: Path, bytes: ByteArray) {
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "refusing to overwrite an existing export file" }
        FileChannel.open(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun readFileNoFollow(path: Path, maxBytes: Int): ByteArray {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && before.size() <= maxBytes) { "file is not a bounded regular file" }
        val output = ByteArrayOutputStream(before.size().toInt().coerceAtMost(maxBytes))
        FileChannel.open(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                buffer.clear()
                val amount = channel.read(buffer)
                if (amount < 0) break
                if (amount == 0) continue
                require(output.size() + amount <= maxBytes) { "file exceeds its size limit" }
                output.write(buffer.array(), 0, amount)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(sameFileSignature(before, after) && output.size().toLong() == before.size()) { "file changed while it was being read" }
        return output.toByteArray()
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try { Files.setPosixFilePermissions(path, permissions) }
        catch (exception: Exception) { throw IllegalStateException("POSIX file permissions are unavailable; refusing to publish", exception) }
    }

    private fun fsyncFile(path: Path) {
        FileChannel.open(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { it.force(true) }
    }

    private fun fsyncDirectory(path: Path) {
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "export sync target is not a regular directory" }
        FileChannel.open(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { it.force(true) }
    }

    private fun fsyncTree(root: Path) {
        val paths = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && attrs.isDirectory) { "export tree contains an unsafe directory" }
                paths.add(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "export tree contains an unsafe entry" }
                fsyncFile(file)
                return FileVisitResult.CONTINUE
            }
        })
        paths.sortedByDescending { it.nameCount }.forEach(::fsyncDirectory)
    }

    private fun ensureSafePrivateOutputRoot(root: Path) {
        require(!Files.isSymbolicLink(root)) { "portable export output directory cannot be a symbolic link" }
        val existing = nearestExistingDirectory(root.parent ?: root)
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "portable export output is not a regular directory" }
        var current: Path? = root
        while (current != null) {
            require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) { "portable export path contains an unsafe directory" }
            fsyncDirectory(current)
            if (current == existing) break
            current = current.parent
        }
        require(current == existing || root == existing) { "portable export durability boundary could not be reached" }
    }

    private fun nearestExistingDirectory(start: Path): Path {
        var current = start
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent ?: error("no existing export parent")
        }
        require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) { "nearest export ancestor is unsafe" }
        return current
    }

    private fun withDestinationLock(root: Path, action: () -> ExportResult): ExportResult {
        val lockPath = root.resolve(".nightmare-export.lock")
        require(!Files.isSymbolicLink(lockPath)) { "portable export lock is unsafe" }
        return FileChannel.open(lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            channel.force(true)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IllegalStateException("another portable export is in progress for this app-private destination")
            lock.use { fsyncDirectory(root); action() }
        }
    }

    private fun validateRelativePath(relative: String) {
        require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative && '\u0000' !in relative &&
            relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "unsafe project-relative path" }
        require(relative.split('/').size <= MAX_PATH_DEPTH) { "project path is nested too deeply" }
        encodeUtf8(relative, "project path")
    }

    private fun sameFileSignature(left: BasicFileAttributes, right: BasicFileAttributes): Boolean =
        left.fileKey() == right.fileKey() && left.isRegularFile == right.isRegularFile && left.size() == right.size() &&
            left.lastModifiedTime() == right.lastModifiedTime()

    private fun fileKey(attributes: BasicFileAttributes): String = attributes.fileKey()?.toString() ?: "size=${attributes.size()};mtime=${attributes.lastModifiedTime().toMillis()}"
    private fun entryIdentity(path: Path): String {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return "${attrs.fileKey() ?: ""}:${attrs.isDirectory}:${attrs.isRegularFile}:${attrs.isSymbolicLink}:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
    }
    private fun attrsHashMode(path: Path): Int = try {
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        (if (PosixFilePermission.OWNER_EXECUTE in permissions) 0x40 else 0) or
            (if (PosixFilePermission.GROUP_EXECUTE in permissions) 0x08 else 0) or
            (if (PosixFilePermission.OTHERS_EXECUTE in permissions) 0x01 else 0)
    } catch (_: Exception) { 0 }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val amount = input.read(buffer)
            if (amount < 0) break
            require(output.size() + amount <= maxBytes) { "file exceeds its size limit" }
            output.write(buffer, 0, amount)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray, description: String): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (exception: Exception) { throw IllegalArgumentException("$description is not valid UTF-8", exception) }

    private fun encodeUtf8(text: String, description: String): ByteArray = try {
        val buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (exception: Exception) { throw IllegalArgumentException("$description cannot be represented as UTF-8", exception) }

    private fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) { buffer.clear(); val read = channel.read(buffer); if (read < 0) break; if (read == 0) continue; digest.update(buffer.array(), 0, read) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    private fun executableModeBits(permissions: Set<PosixFilePermission>): Int =
        (if (PosixFilePermission.OWNER_EXECUTE in permissions) 0x40 else 0) or
            (if (PosixFilePermission.GROUP_EXECUTE in permissions) 0x08 else 0) or
            (if (PosixFilePermission.OTHERS_EXECUTE in permissions) 0x01 else 0)

    private fun updateExecutableModeDigest(digest: MessageDigest, encodedPath: ByteArray, executableMode: Int) {
        digest.update(longBytes(encodedPath.size.toLong()))
        digest.update(encodedPath)
        digest.update(executableMode.toByte())
    }

    private fun unsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun unsignedIntLittleEndian(bytes: ByteArray, offset: Int): Long =
        (unsignedShortLittleEndian(bytes, offset).toLong()) or (unsignedShortLittleEndian(bytes, offset + 2).toLong() shl 16)
    private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int = unsignedIntLittleEndian(bytes, offset).toInt()
    private fun readIntLittleEndian(file: RandomAccessFile): Int =
        file.readUnsignedByte() or (file.readUnsignedByte() shl 8) or (file.readUnsignedByte() shl 16) or (file.readUnsignedByte() shl 24)
    private fun writeShortLittleEndian(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write((value ushr 8) and 0xff)
    }
    private fun writeIntLittleEndian(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write((value ushr 8) and 0xff); file.write((value ushr 16) and 0xff); file.write((value ushr 24) and 0xff)
    }
}
