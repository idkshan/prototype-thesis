package edu.dlsu.esca_3_2526_c3.dms

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads JPEG frames from the ESP32-CAM's MJPEG stream (http://<cam>:81/stream,
 * 07_esp32cam_prototype.ino). Kotlin port of dms/camera/mjpeg.py. Each part is
 *     --frame
 *     Content-Type: image/jpeg
 *     Content-Length: 12345
 *     <JPEG bytes>
 * (HttpURLConnection already removes the chunked transfer encoding).
 */
class MjpegReader(stream: InputStream) {
    private val input = BufferedInputStream(stream, 64 * 1024)

    private fun readLine(): String? {
        val out = ByteArrayOutputStream(80)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) return out.toString("ISO-8859-1").trimEnd('\r')
            out.write(b)
            if (out.size() > 8192) return out.toString("ISO-8859-1") // not a header line
        }
    }

    private fun readHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: throw EOFException("stream closed")
            val l = line.trim()
            if (l.isEmpty()) {
                if (headers.isNotEmpty()) return headers
                continue // blank lines between parts
            }
            if (l.startsWith("--")) continue // boundary
            val colon = l.indexOf(':')
            if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
        }
    }

    /** The next JPEG, or throws EOFException when the stream ends. */
    fun next(): ByteArray {
        val headers = readHeaders()
        val length = headers["content-length"]?.toIntOrNull()
        if (length != null && length > 0) {
            val buf = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(buf, off, length - off)
                if (n < 0) throw EOFException("stream closed inside a frame")
                off += n
            }
            return buf
        }
        // no Content-Length: read up to the JPEG end marker FF D9
        val out = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("stream closed inside a frame")
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            prev = b
        }
    }
}
