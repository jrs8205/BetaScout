package org.jarsi.betascout.testutil

import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/** One request as the server saw it. Header names are lower-cased. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/** What the server answers; [headers] are emitted verbatim (e.g. a Location). When
 *  [raw] is set it is written as the complete response instead, which lets a test
 *  send deliberately malformed framing. */
data class Reply(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val raw: String? = null,
)

/**
 * Minimal single-threaded HTTP server for client tests; com.sun.net.httpserver is
 * not on the android.jar compile classpath, so responses are written by hand.
 * Every request is recorded in [requests]; [handler] decides the reply, defaulting
 * to the plain [status]/[body] fields.
 */
class TinyHttpServer {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile var status = 200
    @Volatile var body = ""

    /** When false the server reads the request but never answers — the client
     *  blocks until its own read timeout, like a stalled endpoint. */
    @Volatile var respond = true

    @Volatile var handler: (RecordedRequest) -> Reply = { Reply(status, body) }

    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    private val thread = Thread {
        try {
            while (true) {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = readRequest(reader) ?: return@use
                    requests += request
                    if (!respond) {
                        Thread.sleep(30_000)
                        return@use
                    }
                    val reply = handler(request)
                    reply.raw?.let { raw ->
                        client.getOutputStream().apply {
                            write(raw.toByteArray())
                            flush()
                        }
                        return@use
                    }
                    val bytes = reply.body.toByteArray()
                    val extra = reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    client.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 ${reply.status} Status\r\n" +
                                    "Content-Type: text/html\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    extra +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        } catch (_: Exception) {
            // The server socket was closed by stop(); the thread just ends.
        }
    }

    private fun readRequest(reader: BufferedReader): RecordedRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buffer = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        } else {
            ""
        }
        return RecordedRequest(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers, body)
    }

    fun start() = thread.apply { isDaemon = true }.start()

    fun stop() = socket.close()
}
