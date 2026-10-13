package com.tyust.course.academic.plugin

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response

/**
 * OkHttp is configured with `retryOnConnectionFailure(false)` because plugin mutations must not be
 * replayed once the request has been sent. This wrapper only retries connection establishment:
 * a failed address is only followed by the next one if the request headers were never started.
 */
internal class PluginConnectFallbackCall(
    private val baseClient: OkHttpClient,
    private val baseListener: EventListener,
    private val request: Request,
    private val maxAttempts: Int,
) : Call {
    private val lock = Any()
    private var executed = false
    private var canceled = false
    private var activeCall: Call? = null

    override fun request(): Request = request

    override fun execute(): Response {
        synchronized(lock) {
            check(!executed) { "Already Executed" }
            executed = true
            if (canceled) throw IOException("Canceled")
        }

        var lastFailure: IOException? = null
        for (offset in 0 until maxAttempts) {
            val tracker = Tracker(baseListener)
            val attemptClient = baseClient.newBuilder()
                .dns(RotatingDns(baseClient.dns, offset))
                .eventListener(tracker)
                .build()
            val call = attemptClient.newCall(request)
            synchronized(lock) {
                activeCall = call
                if (canceled) call.cancel()
            }
            try {
                val response = call.execute()
                return response
            } catch (e: IOException) {
                if (canceled || tracker.requestSent || !tracker.connectFailed) throw e
                lastFailure = e
            } finally {
                synchronized(lock) { activeCall = null }
            }
        }
        throw lastFailure ?: IOException("No address could be reached")
    }

    override fun enqueue(responseCallback: okhttp3.Callback) {
        throw UnsupportedOperationException("Plugin HTTP calls execute synchronously")
    }

    override fun cancel() {
        synchronized(lock) {
            canceled = true
            activeCall?.cancel()
        }
    }

    override fun isExecuted(): Boolean = synchronized(lock) { executed }

    override fun isCanceled(): Boolean = synchronized(lock) { canceled }

    override fun timeout(): okio.Timeout = okio.Timeout()
        .timeout(baseClient.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)

    override fun clone(): Call = PluginConnectFallbackCall(baseClient, baseListener, request, maxAttempts)

    private class Tracker(private val base: EventListener) : EventListener() {
        @Volatile var connectFailed = false
            private set
        @Volatile var requestSent = false
            private set

        override fun callStart(call: Call) = base.callStart(call)
        override fun dnsStart(call: Call, domainName: String) = base.dnsStart(call, domainName)
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
            base.dnsEnd(call, domainName, inetAddressList)
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) =
            base.connectStart(call, inetSocketAddress, proxy)
        override fun connectFailed(
            call: Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            connectFailed = true
            base.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun secureConnectStart(call: Call) = base.secureConnectStart(call)
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) =
            base.secureConnectEnd(call, handshake)
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) =
            base.connectionAcquired(call, connection)
        override fun requestHeadersStart(call: Call) {
            requestSent = true
            base.requestHeadersStart(call)
        }
        override fun responseHeadersStart(call: Call) = base.responseHeadersStart(call)
        override fun responseHeadersEnd(call: Call, response: Response) =
            base.responseHeadersEnd(call, response)
        override fun responseBodyEnd(call: Call, byteCount: Long) =
            base.responseBodyEnd(call, byteCount)
        override fun callEnd(call: Call) = base.callEnd(call)
        override fun callFailed(call: Call, ioe: IOException) = base.callFailed(call, ioe)
    }

    private class RotatingDns(private val delegate: Dns, private val offset: Int) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (offset == 0 || addresses.size < 2) return addresses
            val start = offset % addresses.size
            return addresses.drop(start) + addresses.take(start)
        }
    }
}

internal object PluginConnectFallback {
    const val MAX_ATTEMPTS = 4
}
