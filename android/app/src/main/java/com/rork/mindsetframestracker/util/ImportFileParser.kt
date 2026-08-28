package com.rork.mindsetframestracker.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/** Files above this size aren't worth reading for a habit list and risk loading huge binaries. */
private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

sealed interface ImportOutcome {
    data class Success(val lines: List<String>) : ImportOutcome
    data object NotText : ImportOutcome
    data object TooLarge : ImportOutcome
    data object ReadFailed : ImportOutcome
}

/**
 * Reads whatever file [uri] points to and returns its content as a list of
 * non-empty, trimmed lines - regardless of whether it's a plain text file,
 * a Word document (.docx), or a PDF (.pdf). Format is detected from the raw
 * bytes (magic numbers), not the file extension, since many pickers don't
 * report a reliable mime type or extension.
 *
 * Must be called off the main thread - PDF parsing in particular can take
 * real time on a large document.
 */
fun readImportedFile(context: Context, uri: Uri): ImportOutcome {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(MAX_IMPORT_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read == -1) break
                total += read
            }
            if (total > MAX_IMPORT_BYTES) return ImportOutcome.TooLarge
            buffer.copyOf(total)
        } ?: return ImportOutcome.ReadFailed
    } catch (e: Exception) {
        return ImportOutcome.ReadFailed
    }

    if (bytes.isEmpty()) return ImportOutcome.Success(emptyList())

    val text = when (detectFormat(bytes)) {
        FileFormat.PDF -> extractPdfText(context, bytes) ?: return ImportOutcome.NotText
        FileFormat.DOCX -> extractDocxText(bytes) ?: return ImportOutcome.NotText
        FileFormat.PLAIN_TEXT -> decodeStrictUtf8(bytes) ?: return ImportOutcome.NotText
    }

    return ImportOutcome.Success(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
}

private enum class FileFormat { PDF, DOCX, PLAIN_TEXT }

/** Sniffs the real format from magic bytes rather than trusting file name/mime. */
private fun detectFormat(bytes: ByteArray): FileFormat {
    val isPdf = bytes.size >= 5 &&
        bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
    if (isPdf) return FileFormat.PDF

    // ZIP local-file-header signature "PK\x03\x04" - docx (and xlsx/pptx) are zips.
    val isZip = bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
    if (isZip && containsDocxDocumentXml(bytes)) return FileFormat.DOCX

    return FileFormat.PLAIN_TEXT
}

private fun containsDocxDocumentXml(bytes: ByteArray): Boolean = try {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.any { it.name == "word/document.xml" }
    }
} catch (e: Exception) {
    false
}

/** Pulls the visible text out of a .docx's word/document.xml, one paragraph per line. */
private fun extractDocxText(bytes: ByteArray): String? = try {
    var xml: String? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                xml = zip.readBytes().toString(StandardCharsets.UTF_8)
                break
            }
            entry = zip.nextEntry
        }
    }
    xml?.let { docXml ->
        // Word hard-wraps paragraphs as <w:p>...</w:p> - turn each into one line,
        // then strip every remaining XML tag and unescape the handful of XML entities.
        docXml
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
} catch (e: Exception) {
    null
}

/** Extracts plain text from a .pdf using PdfBox-Android. */
private fun extractPdfText(context: Context, bytes: ByteArray): String? = try {
    PDFBoxResourceLoader.init(context.applicationContext)
    PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
        PDFTextStripper().getText(document)
    }
} catch (e: Exception) {
    null
}

/**
 * Strict UTF-8 decode (CodingErrorAction.REPORT) - throws on any byte
 * sequence that isn't valid UTF-8 text, which is what rejects unrecognized
 * binary formats. Real plain-text files, regardless of extension, decode
 * cleanly.
 */
private fun decodeStrictUtf8(bytes: ByteArray): String? {
    val decoder = StandardCharsets.UTF_8.newDecoder().apply {
        onMalformedInput(CodingErrorAction.REPORT)
        onUnmappableCharacter(CodingErrorAction.REPORT)
    }
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    } catch (e: Exception) {
        null
    }
}
