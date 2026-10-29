package com.embyverse.core

import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.HttpResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

object Fixtures {
    /** Raíz del repositorio (variable EMBYVERSE_ROOT o búsqueda hacia arriba de tests/fixtures). */
    val root: File by lazy {
        System.getenv("EMBYVERSE_ROOT")?.let { return@lazy File(it) }
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "tests/fixtures").isDirectory) d = d.parentFile
        d ?: error("No se encuentra tests/fixtures; define EMBYVERSE_ROOT")
    }
    fun file(path: String) = File(root, path)
    fun text(path: String) = file(path).readText()
}

/** Cliente HTTP real basado en HttpURLConnection, para probar contra un servidor HTTP local. */
class JdkHttpFetcher(private val timeoutMs: Int = 3000) : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val stream = if (code >= 400) (c.errorStream ?: "".byteInputStream()) else c.inputStream
        HttpResponse(code, c.contentType, stream, c.url.toString())
    }
}

/** Servidor HTTP local que simula un proveedor (M3U o API Xtream) con respuestas controladas. */
class FakeProvider : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val requests = java.util.Collections.synchronizedList(ArrayList<String>())
    private val routes = LinkedHashMap<String, (Map<String, String>) -> Triple<Int, String, Long>>()

    init {
        server.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            requests += ex.requestURI.toString()
            val key = ex.requestURI.path + (q["action"]?.let { "#$it" } ?: "")
            val handler = routes[key] ?: routes[ex.requestURI.path]
            val (code, body, delay) = handler?.invoke(q) ?: Triple(404, "not found", 0L)
            if (delay > 0) Thread.sleep(delay)
            val bytes = body.toByteArray()
            try {
                ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) { } finally { ex.close() }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    fun route(pathAndAction: String, handler: (Map<String, String>) -> Triple<Int, String, Long>) { routes[pathAndAction] = handler }
    fun ok(pathAndAction: String, body: String) = route(pathAndAction) { Triple(200, body, 0L) }
    override fun close() = server.stop(0)
}
