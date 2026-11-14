package com.thenightmare.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Read-only URI grant surface for a verified app-private export pair. */
class PortableExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row: Array<Any?> = Array(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns.toTypedArray()).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = when (resolve(uri).name) {
        PortableProjectExporter.ARCHIVE_NAME -> "application/zip"
        PortableProjectExporter.MANIFEST_NAME -> "application/json"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("portable exports are read-only")
        val file = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("portable exports are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("portable exports are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("portable exports are read-only")

    private fun resolve(uri: Uri): File {
        val appContext = context ?: throw FileNotFoundException("provider is not attached")
        if (uri.scheme != "content" || uri.authority != "${appContext.packageName}.portable-exports" || uri.pathSegments.size != 2) {
            throw FileNotFoundException("invalid portable export URI")
        }
        val exportId = uri.pathSegments[0]
        val fileName = uri.pathSegments[1]
        if (!exportId.startsWith(PortableProjectExporter.STAGING_PREFIX) ||
            exportId.any { !(it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }) {
            throw FileNotFoundException("invalid portable export identifier")
        }
        if (fileName != PortableProjectExporter.ARCHIVE_NAME && fileName != PortableProjectExporter.MANIFEST_NAME) {
            throw FileNotFoundException("only the verified ZIP and manifest may be shared")
        }
        val outputRoot = File(appContext.filesDir, "portable-exports")
        val directory = File(outputRoot, exportId)
        val path = directory.toPath()
        if (Files.isSymbolicLink(outputRoot.toPath()) || Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || directory.canonicalFile.parentFile != outputRoot.canonicalFile) {
            throw FileNotFoundException("portable export is not a safe app-private directory")
        }
        try {
            PortableProjectExporter.verifyExportPair(directory)
        } catch (exception: Exception) {
            throw FileNotFoundException("portable export pair failed verification: ${exception.message}")
        }
        val file = File(directory, fileName)
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            file.canonicalFile.parentFile != directory.canonicalFile) {
            throw FileNotFoundException("portable export file is missing or unsafe")
        }
        return file
    }
}
