package com.hotsharefile.hotsharefile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class SimpleHttpServer(
    private val context: Context,
    val selectedFiles: List<Uri>
) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _progressFlow = MutableSharedFlow<ProgressUpdate>()
    val progressFlow = _progressFlow.asSharedFlow()

    companion object {
        private const val PORT = 8888
        private const val BUFFER_SIZE = 1024 * 1024 * 4
    }

    fun startServer() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }
    }

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.sendBufferSize = BUFFER_SIZE
            socket.receiveBufferSize = BUFFER_SIZE
            socket.tcpNoDelay = true

            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            var contentLength = 0L
            var fileName = "unknown_file"
            var fileSize = "0"
            var fileIndex = "0/0"

            var line: String?
            while (readLine(input).also { line = it } != null && !line.isNullOrEmpty()) {
                val currentLine = line ?: break
                val idx = currentLine.indexOf(":")
                if (idx == -1) continue

                val header = currentLine.substring(0, idx).trim()
                val value = currentLine.substring(idx + 1).trim()

                when {
                    header.equals("Content-Length", ignoreCase = true) -> contentLength = value.toLongOrNull() ?: 0L
                    header.equals("X-File-Name", ignoreCase = true) -> fileName = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                    header.equals("X-File-Index", ignoreCase = true) -> fileIndex = value
                    header.equals("X-File-Size", ignoreCase = true) -> fileSize = value
                }
            }

            if (method == "GET") {
                handleGet(path, output)
            } else if (method == "POST") {
                handlePost(path, input, output, contentLength, fileName, fileIndex)
            }

            output.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private suspend fun handleGet(path: String, out: OutputStream) {
        if (path == "/") {
            val html = loadHtml("page.html")
            writeResponse(out, "text/html; charset=UTF-8", html.toByteArray(StandardCharsets.UTF_8))
            return
        }

        if (path == "/q") {
            val msg = "Server shutting down..."
            writeResponse(out, "text/plain; charset=UTF-8", msg.toByteArray(StandardCharsets.UTF_8))
            stopServer()
            return
        }

        if (path == "/files") {
            val items = StringBuilder()
            selectedFiles.forEachIndexed { i, uri ->
                items.append("<li><a href=\"/download/")
                    .append(i)
                    .append("\">")
                    .append(getFileName(uri))
                    .append("</a></li>")
            }
            val html = loadHtml("paged.html").replace("{{items}}", items.toString())
            writeResponse(out, "text/html; charset=UTF-8", html.toByteArray(StandardCharsets.UTF_8))
            return
        }

        if (path.startsWith("/download/")) {
            val parts = path.split("/")
            if (parts.size < 3) {
                write404(out)
                return
            }
            val idx = parts[2].toIntOrNull() ?: -1
            if (idx !in selectedFiles.indices) {
                write404(out)
                return
            }

            val uri = selectedFiles[idx]
            var fileName = "unknown"
            var size = -1L

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: "unknown"
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: run {
                write404(out)
                return
            }

            BufferedInputStream(inputStream, BUFFER_SIZE).use { fis ->
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: application/octet-stream\r\n")

                if (size != -1L) {
                    headerBuilder.append("Content-Length: ").append(size).append("\r\n")
                        .append("Connection: keep-alive\r\n")
                } else {
                    headerBuilder.append("Connection: close\r\n")
                }
                headerBuilder.append("Content-Disposition: attachment; filename=\"")
                    .append(fileName).append("\"\r\n\r\n")

                out.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))

                val buffer = ByteArray(BUFFER_SIZE)
                var n: Int
                var totalSent = 0L
                var lastPercent = -1

                while (fis.read(buffer).also { n = it } != -1) {
                    out.write(buffer, 0, n)
                    totalSent += n

                    val percent = if (size > 0) ((totalSent * 100L) / size).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _progressFlow.emit(ProgressUpdate('0', fileName, (idx + 1).toString(), percent))
                    }
                }
                out.flush()
            }
            return
        }

        write404(out)
    }

    private suspend fun handlePost(
        path: String,
        input: InputStream,
        out: OutputStream,
        length: Long,
        name: String,
        fileIndex: String
    ) {
        if (path != "/upload") {
            write404(out)
            return
        }

        val resolver = context.contentResolver
        var uri: Uri? = null

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/uploads")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create file in Downloads")

            resolver.openOutputStream(uri)?.use { fos ->
                var remaining = length
                var totalRead = 0L
                var lastPercent = -1
                val buffer = ByteArray(BUFFER_SIZE)

                while (remaining > 0) {
                    val toRead = buffer.size.toLong().coerceAtMost(remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break

                    fos.write(buffer, 0, read)
                    remaining -= read
                    totalRead += read

                    val percent = if (length > 0) ((totalRead * 100L) / length).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _progressFlow.emit(ProgressUpdate('1', name, fileIndex, percent))
                    }
                }
                fos.flush()
            }

            values.clear()
            values.apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)

        } catch (e: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            throw e
        }

        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        out.write(resp.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readLine(isStream: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (isStream.read().also { c = it } != -1) {
            if (c == '\r'.code) {
                val next = isStream.read()
                if (next == '\n'.code) break
                sb.append(c.toChar()).append(next.toChar())
            } else if (c == '\n'.code) {
                break
            } else {
                sb.append(c.toChar())
            }
        }
        if (sb.isEmpty() && c == -1) return null
        return sb.toString()
    }

    private fun writeResponse(out: OutputStream, type: String, data: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: keep-alive\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(data)
    }

    private fun write404(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun loadHtml(fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        return result ?: uri.lastPathSegment ?: "unknown"
    }
}