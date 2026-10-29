package com.embyverse.app.data.net

import com.embyverse.app.BuildConfig
import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Network {
    val USER_AGENT = "EmbyVerse/${BuildConfig.VERSION_NAME} (Android)"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // las listas grandes pueden tardar; el readTimeout corta conexiones colgadas
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
            else chain.proceed(req)
        }
        // Intencionadamente SIN HttpLoggingInterceptor: las URLs Xtream contienen credenciales.
        .build()
}

/** Implementación Android de [HttpFetcher] (cancelable con la corrutina). */
class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.newCall(builder.build()).await()
        val body = response.body
        HttpResponse(response.code, response.header("Content-Type"), body.byteStream(), response.request.url.toString())
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
