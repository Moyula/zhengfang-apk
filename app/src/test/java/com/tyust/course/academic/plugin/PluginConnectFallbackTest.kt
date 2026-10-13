package com.tyust.course.academic.plugin

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginConnectFallbackTest {
    private class CountingDns(private val addresses: List<InetAddress>) : Dns {
        val lookups = AtomicInteger()

        override fun lookup(hostname: String): List<InetAddress> {
            lookups.incrementAndGet()
            return addresses
        }
    }

    private fun client(dns: Dns, interceptor: Interceptor? = null): OkHttpClient =
        OkHttpClient.Builder()
            .dns(dns)
            .retryOnConnectionFailure(false)
            .connectTimeout(1, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .apply { interceptor?.let(::addInterceptor) }
            .build()

    @Test
    fun `falls back when ipv4 is first and only ipv6 is reachable`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("::1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("::1")))

        val response = PluginConnectFallbackCall(
            client(dns),
            EventListener.NONE,
            request,
            PluginConnectFallback.MAX_ATTEMPTS,
        ).execute()

        response.use { assertEquals("ok", it.body!!.string()) }
        assertEquals(2, dns.lookups.get())
        server.shutdown()
    }

    @Test
    fun `falls back when ipv6 is first and only ipv4 is reachable`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")))

        val response = PluginConnectFallbackCall(
            client(dns),
            EventListener.NONE,
            request,
            PluginConnectFallback.MAX_ATTEMPTS,
        ).execute()

        response.use { assertEquals("ok", it.body!!.string()) }
        assertEquals(2, dns.lookups.get())
        server.shutdown()
    }

    @Test
    fun `keeps system order when ipv4 is first and only ipv4 is reachable`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")))

        val response = PluginConnectFallbackCall(
            client(dns),
            EventListener.NONE,
            request,
            PluginConnectFallback.MAX_ATTEMPTS,
        ).execute()

        response.use { assertEquals("ok", it.body!!.string()) }
        assertEquals(1, dns.lookups.get())
        server.shutdown()
    }

    @Test
    fun `keeps system order when ipv6 is first and only ipv6 is reachable`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("::1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.2")))

        val response = PluginConnectFallbackCall(
            client(dns),
            EventListener.NONE,
            request,
            PluginConnectFallback.MAX_ATTEMPTS,
        ).execute()

        response.use { assertEquals("ok", it.body!!.string()) }
        assertEquals(1, dns.lookups.get())
        server.shutdown()
    }

    @Test
    fun `falls back through multiple unreachable addresses`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(
            listOf(
                InetAddress.getByName("127.0.0.2"),
                InetAddress.getByName("127.0.0.3"),
                InetAddress.getByName("127.0.0.1"),
            ),
        )

        val response = PluginConnectFallbackCall(
            client(dns),
            EventListener.NONE,
            request,
            PluginConnectFallback.MAX_ATTEMPTS,
        ).execute()

        response.use { assertEquals("ok", it.body!!.string()) }
        assertEquals(3, dns.lookups.get())
        server.shutdown()
    }

    @Test
    fun `does not retry after request headers have been sent`() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://plugin-fallback.test:${server.port}/".toHttpUrl()).build()
        val dns = CountingDns(listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.1")))
        val failAfterRequest = Interceptor { chain ->
            chain.proceed(chain.request()).use { throw IOException("failure after request") }
        }

        val exception = assertThrows(IOException::class.java) {
            PluginConnectFallbackCall(
                client(dns, failAfterRequest),
                EventListener.NONE,
                request,
                PluginConnectFallback.MAX_ATTEMPTS,
            ).execute()
        }

        assertEquals("failure after request", exception.message)
        assertEquals(1, dns.lookups.get())
        server.shutdown()
    }
}
