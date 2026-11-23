package com.stvault.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Lightweight local loopback Streamable HTTP MCP server (127.0.0.1 only).
 * Fully compliant with MCP Streamable HTTP (2025-03-26) for TauriTavern's Rust rmcp client:
 * - Supports both Content-Length and Transfer-Encoding: chunked request bodies
 * - Returns 405 Method Not Allowed on GET /mcp (no server-initiated SSE stream)
 * - Echoes client protocolVersion on initialize
 */
class TavernBridgeServer(private val manager: TavernBridgeManager) {

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sessionId = UUID.randomUUID().toString()

    fun start(): Int {
        if (isRunning) return boundPort
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in CANDIDATE_PORTS) {
            try {
                val ss = ServerSocket(port, 32, loopback)
                serverSocket = ss
                boundPort = port
                isRunning = true
                pool.execute { acceptLoop(ss) }
                return port
            } catch (_: Exception) {
            }
        }
        return -1
    }

    fun stop() {
        isRunning = false
        boundPort = 0
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            try {
                val client = ss.accept()
                pool.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val inp = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                writeHttp(out, 204, "No Content", "")
                return
            }

            if (path != "/mcp") {
                writeHttp(out, 404, "Not Found", """{"error":"not_found"}""")
                return
            }

            // Per MCP Streamable HTTP spec: return 405 if server doesn't open an SSE stream on GET
            if (method == "GET") {
                writeHttp(out, 405, "Method Not Allowed", "")
                return
            }

            if (method == "DELETE") {
                writeHttp(out, 204, "No Content", "")
                return
            }

            val isChunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = when {
                isChunked -> readChunkedBody(inp)
                contentLength > 0 && contentLength <= 16 * 1024 * 1024 -> readExact(inp, contentLength)
                else -> ByteArray(0)
            }

            val bodyText = String(bodyBytes, Charsets.UTF_8).trim()
            if (bodyText.isEmpty()) {
                writeHttp(out, 400, "Bad Request", """{"error":"empty_body"}""")
                return
            }

            val req = JSONObject(bodyText)
            val rpcMethod = req.optString("method", "")
            val hasId = req.has("id") && !req.isNull("id")
            val idVal = if (hasId) req.get("id") else null

            manager.recordDiag("收到 $rpcMethod")

            if (rpcMethod.startsWith("notifications/") || !hasId) {
                writeHttp(out, 202, "Accepted", "")
                return
            }

            val resp = when (rpcMethod) {
                "initialize" -> {
                    val clientProto = req.optJSONObject("params")
                        ?.optString("protocolVersion", "")
                        ?.takeIf { it.isNotBlank() }
                        ?: "2025-03-26"
                    jsonRpcResult(idVal, JSONObject().apply {
                        put("protocolVersion", clientProto)
                        put("capabilities", JSONObject().apply {
                            put("tools", JSONObject().apply { put("listChanged", false) })
                        })
                        put("serverInfo", JSONObject().apply {
                            put("name", "stvault-bridge")
                            put("version", "1.8.2")
                        })
                    })
                }

                "ping" -> jsonRpcResult(idVal, JSONObject())

                "tools/list" -> jsonRpcResult(idVal, JSONObject().apply {
                    put("tools", buildToolDefinitions())
                })

                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    val toolName = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val toolOut = manager.handleToolCall(toolName, args)
                    val isErr = !toolOut.optBoolean("ok", true)
                    jsonRpcResult(idVal, JSONObject().apply {
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", toolOut.toString())
                        }))
                        put("isError", isErr)
                    })
                }

                else -> JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", idVal)
                    put("error", JSONObject().apply {
                        put("code", -32601)
                        put("message", "Method not found: $rpcMethod")
                    })
                }
            }

            writeHttp(out, 200, "OK", resp.toString())
        } catch (e: Exception) {
            manager.recordDiag("请求异常：${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun jsonRpcResult(id: Any?, result: JSONObject): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun buildToolDefinitions(): JSONArray {
        val arr = JSONArray()
        fun addTool(name: String, desc: String, vararg props: Pair<String, String>) {
            val propsObj = JSONObject()
            for ((k, t) in props) {
                propsObj.put(k, JSONObject().put("type", t))
            }
            arr.put(JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", propsObj)
                })
            })
        }

        addTool("stvault_ping", "Ping stvault local bridge")
        addTool("stvault_sync_inventory", "Sync installed resource names from Tavern",
            "themes" to "array", "characters" to "array", "worldbooks" to "array", "presets" to "array")
        addTool("stvault_outbox_list", "List items queued to install into Tavern")
        addTool("stvault_outbox_fetch", "Fetch base64 chunk of an outbox item",
            "id" to "string", "offset" to "integer", "length" to "integer")
        addTool("stvault_outbox_ack", "Acknowledge an outbox item installed", "id" to "string")
        addTool("stvault_pull_poll", "Check if user requested pulling from Tavern")
        addTool("stvault_pull_done", "Report pull completion", "summary" to "string")
        addTool("stvault_push_begin", "Start chunked upload to stvault",
            "kind" to "string", "fileName" to "string", "bytes" to "integer")
        addTool("stvault_push_chunk", "Send chunk of upload",
            "opId" to "string", "index" to "integer", "data" to "string")
        addTool("stvault_push_end", "Finish upload and ingest", "opId" to "string")
        addTool("stvault_push_fast", "Single-shot fast upload for items <= 512KB",
            "kind" to "string", "fileName" to "string", "data" to "string", "tags" to "array")
        addTool("stvault_push_batch", "Batch upload multiple small items in one round-trip",
            "items" to "array")
        addTool("stvault_sync_theme_tags", "Bidirectional sync of theme tags between st-theme-companion and stvault",
            "mode" to "string", "tagPaths" to "array", "themeTags" to "object")
        return arr
    }

    private fun writeHttp(out: OutputStream, code: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText).append("\r\n")
        if (bodyBytes.isNotEmpty()) {
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
        }
        sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: POST, GET, DELETE, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Accept, Mcp-Session-Id\r\n")
        sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        var b = inp.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) baos.write(b)
            b = inp.read()
        }
        return baos.toString("UTF-8")
    }

    private fun readExact(inp: BufferedInputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    private fun readChunkedBody(inp: BufferedInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        var total = 0
        while (total < 16 * 1024 * 1024) {
            val sizeLine = readLine(inp)?.trim()?.substringBefore(';') ?: break
            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                readLine(inp) // trailing CRLF
                break
            }
            val chunk = readExact(inp, chunkSize)
            baos.write(chunk)
            total += chunk.size
            readLine(inp) // chunk terminator CRLF
        }
        return baos.toByteArray()
    }

    companion object {
        val CANDIDATE_PORTS = listOf(8796, 8797, 8798, 8799, 8800)
    }
}
