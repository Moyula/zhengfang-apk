package com.tyust.course.academic.plugin

import android.util.Base64
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PluginMapRequestAuthTest {
    @Test fun requestProofMatchesIndependentNodeHmacAndBindsTheQuery() {
        fun proof(query: String): String {
            val header = PluginMapRequestAuth.signedHeader("synthetic-map-client-credential-0123456789", "com.tyust.course", 120,
                "1755ba0b0efa6e20c1c96cf9b216569851b6d399225044da05d20c547f71d671", query,
                1791810000L, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            return JSONObject(String(Base64.decode(header, Base64.URL_SAFE), Charsets.UTF_8)).getString("signature")
        }
        assertEquals("qllTjL7hYYxnnu8hRVXuk4f0GuEC7B5WtbJplG_Hi6E", proof("太原市 鼎泰悦郡"))
        assertNotEquals(proof("太原市 鼎泰悦郡"), proof("太原市 武宿机场"))
        assertEquals(proof("太原市 鼎泰悦郡"), proof(" 太原市 鼎泰悦郡 "))
    }

    @Test fun pluginHeadersAreStrippedWhenRedirectedOrNoHostContextIsAvailable() {
        assertTrue(PluginMapRequestAuth.accepts(Request.Builder().url("https://plugins.hidisiwa.xyz/api/maps/v1/search?q=place").build()))
        for (target in listOf("https://outside.test/api/maps/v1/search?q=place", "https://plugins.hidisiwa.xyz:444/api/maps/v1/search?q=place",
            "http://plugins.hidisiwa.xyz/api/maps/v1/search?q=place", "https://plugins.hidisiwa.xyz/api/maps/v1/client-config",
            "https://plugins.hidisiwa.xyz/api/maps/v1/search?q=one&q=two", "https://plugins.hidisiwa.xyz/api/maps/v1/search?q=place&key=extra")) {
            val builder = Request.Builder().url(target).header(PluginMapRequestAuth.HEADER, "plugin-supplied-proof")
            assertFalse(PluginMapRequestAuth.accepts(builder.build()))
            assertNull(PluginMapRequestAuth.authorize(builder, context = null).build().header(PluginMapRequestAuth.HEADER))
        }
        assertFalse(PluginMapRequestAuth.accepts(Request.Builder().url("https://plugins.hidisiwa.xyz/api/maps/v1/search?q=place").head().build()))
    }
}
