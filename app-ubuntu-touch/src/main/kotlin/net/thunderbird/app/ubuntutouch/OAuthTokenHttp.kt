package net.thunderbird.app.ubuntutouch

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal fun interface OAuthTokenHttp {
    suspend fun post(endpoint: URI, parameters: Map<String, String>): OAuthHttpReply
}

internal class OAuthHttpReply(val status: Int, val contentType: String, val body: ByteArray) {
    override fun toString(): String = "OAuthHttpReply(redacted)"
}

/** Default JVM trust and hostname verification; injected trust is for a separately scoped controlled fixture. */
internal class JdkOAuthTokenHttp(
    context: SSLContext = SSLContext.getDefault(),
    private val timeoutMillis: Long = DEFAULT_TOKEN_TIMEOUT,
) : OAuthTokenHttp, OAuthIdentityHttp, AutoCloseable {
    private val client = HttpClient.newBuilder()
        .sslContext(context)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(timeoutMillis))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    init {
        require(timeoutMillis in 1..DEFAULT_TOKEN_TIMEOUT)
    }

    override suspend fun post(endpoint: URI, parameters: Map<String, String>): OAuthHttpReply {
        requireTokenEndpoint(endpoint)
        val form = parameters.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form, Charsets.UTF_8))
            .build()
        return withTimeout(timeoutMillis) { receive(request) }
    }

    override suspend fun get(endpoint: URI, accessToken: String): OAuthHttpReply {
        requireTokenEndpoint(endpoint)
        require(accessToken.length in 1..MAX_IDENTITY_ACCESS_TOKEN && accessToken.all { it in '!'..'~' })
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        return withTimeout(timeoutMillis) { receive(request) }
    }

    private suspend fun receive(request: HttpRequest): OAuthHttpReply = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val stream = AtomicReference<InputStream?>()
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            continuation.invokeOnCancellation {
                future.cancel(true)
                stream.get()?.closeAfterCancellation()
            }
            future.whenComplete { response, failure ->
                if (failure != null) {
                    continuation.resumeWithException(OAuthTokenException(OAuthTokenFailure.UNAVAILABLE))
                } else {
                    val body = response.body()
                    stream.set(body)
                    if (!continuation.isActive) {
                        body.closeAfterCancellation()
                    } else {
                        launch(Dispatchers.IO) {
                            try {
                                val bytes = body.use { it.readNBytes(MAX_TOKEN_RESPONSE + 1) }
                                if (bytes.size > MAX_TOKEN_RESPONSE) {
                                    bytes.fill(0)
                                    continuation.resumeWithException(
                                        OAuthTokenException(OAuthTokenFailure.INVALID_RESPONSE),
                                    )
                                } else {
                                    continuation.resume(
                                        OAuthHttpReply(
                                            response.statusCode(),
                                            response.headers().firstValue("Content-Type").orElse(""),
                                            bytes,
                                        ),
                                        onCancellation = { _, value, _ -> value.body.fill(0) },
                                    )
                                }
                            } catch (_: IOException) {
                                continuation.resumeWithException(OAuthTokenException(OAuthTokenFailure.UNAVAILABLE))
                            } finally {
                                stream.set(null)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() = client.shutdownNow()
}

internal fun requireTokenEndpoint(endpoint: URI) {
    require(
        endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
            endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null,
    ) { "Invalid OAuth token endpoint" }
}

private fun InputStream.closeAfterCancellation() {
    try {
        close()
    } catch (_: IOException) {
        // Cancellation remains the primary outcome. No response bytes or transport error are logged.
    }
}

internal const val MAX_TOKEN_RESPONSE = 65_536
private const val DEFAULT_TOKEN_TIMEOUT = 30_000L

private const val MAX_IDENTITY_ACCESS_TOKEN = 16384
