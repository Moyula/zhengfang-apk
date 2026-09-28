package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginAcademicTokenTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var school: SchoolConfig
    private lateinit var source: PluginPackage
    private lateinit var session: AcademicSession
    private val installed = mutableListOf<String>()
    private val secret = "synthetic-response-token"
    private val interaction = object : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path".toHttpUrl()
    private fun rule() = JSONObject("""{"response":{"path":"/api/login","method":"POST","jsonPointer":"/data/token"},"request":{"pathPrefix":"/api","header":"X-Token"}}""")
    private fun schoolManifest(value: SchoolConfig = school) = JSONObject().put("id", value.id).put("name", value.name)
        .put("domain", value.domain).put("protocol", value.protocol).put("basePath", value.basePath).put("academicSystem", value.academicSystem)
    private fun network() = JSONArray().put(JSONObject().put("origin", url("/").toString().trimEnd('/')).put("pathPrefix", "/")
        .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("auth", "query", "mutation"))))
    private fun install(manifest: JSONObject): PluginPackage {
        // Host integration fixtures have no JS execution; protocol execution is tested in QuickJS.
        val js = ByteArray(0)
        manifest.put("entry", "index.js").put("files", JSONObject().put("index.js", PluginJson.sha256(js)))
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name, data) in listOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to js)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        } }.toByteArray()
        return runBlocking { AcademicProviderRegistry.packages().install(bytes, allowDevelopment = true) }.also { installed += it.manifest.id; AcademicProviderRegistry.reload() }
    }
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = RuntimeEnvironment.getApplication(); UserManager.getInstance().init(app)
        AcademicProviderRegistry.initialize(app); PluginPages.initialize(app)
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        school = SchoolConfig("token-school", "Synthetic school", "127.0.0.1:${server.port}", "http").apply { basePath = "/jw"; academicSystem = "zf" }
        source = install(JSONObject().put("id", "test.token-source").put("name", "Synthetic adapter").put("version", "1.0.0")
            .put("kind", "independent").put("apiVersion", 3).put("capabilities", JSONArray(PluginManifest.AUTH))
            .put("school", schoolManifest()).put("network", network()).put("academicSessionToken", rule())
            .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2))))
        AcademicProviderRegistry.choose(school, source.manifest.id)
        val user = UserManager.getInstance(); user.init(app); user.currentSchool = school; user.studentId = "synthetic-student"; user.saveCookieLogin("fixture=login")
        session = AcademicGatewayFactory.sharedSession(school, user.currentAccountStorageKey)!!
        assertEquals(url("/jw").toString(), session.baseUrl.trimEnd('/'))
        session.cookies.clear()
    }
    @After fun cleanup() {
        if (::session.isInitialized) session.retire()
        if (::school.isInitialized) AcademicProviderRegistry.choose(school, null)
        installed.distinct().forEach { AcademicProviderRegistry.packages().deactivate(it) }; AcademicProviderRegistry.reload()
        server.shutdown(); Dispatchers.resetMain()
    }
    private fun capture(body: String = """{"data":{"token":"$secret"}}""", status: Int = 200, result: String = "authenticated"): PluginAcademicTokenCapture {
        val op = PluginOperation(session, source.manifest, "auth.start")
        val capture = PluginAcademicTokenCapture(op, source)
        val host = PluginHost(op, app.cacheDir, captureToken = capture::capture)
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        host.call("http", JSONObject().put("url", url("/jw/api/login").toString()).put("method", "POST").put("purpose", "auth"))
        server.takeRequest(1, TimeUnit.SECONDS)
        assertNull(session.pluginToken)
        capture.publish(JSONObject().put("status", result))
        assertFalse(host.report().toString().contains(secret)); op.close()
        return capture
    }
    private fun caller(id: String) = install(JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 3).put("kind", "native")
        .put("capabilities", JSONArray()).put("permissions", JSONArray(listOf("academic.session", "network"))).put("network", network())
        .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2)))
        .put("matches", JSONArray().put(JSONObject().put("host", "127.0.0.1").put("port", server.port).put("pathPrefix", "/jw")))
        .put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray())))
    private fun access(pkg: PluginPackage) = PluginAcademicSession(app, pkg, { true })
    private fun request(path: String = "/jw/api/me") = JSONObject().put("url", url(path).toString()).put("purpose", "query")
    private fun host(pkg: PluginPackage, access: PluginAcademicSession, grant: String): PluginHost = PluginHost(
        PluginOperation(session, pkg.manifest, "host.effect", scopeStillActive = { access.requireGrant(grant); true }), app.cacheDir,
        access.cookies(grant), sharedToken = { access.tokenHeader(grant, it) }, sharedRequest = { url, method, purpose, form -> access.requireRequest(grant, url, method, purpose, form) })
    private fun effect(name: String, input: JSONObject, version: Int = 1) = JSONObject().put("id", "test-effect").put("capability", name).put("version", version).put("input", input)

    @Test fun responseTokenIsSharedByTwoNativePluginsWithoutReturningTheSecret() = runBlocking {
        capture()
        for (id in listOf("test.author-one", "test.author-two")) {
            val pkg = caller(id)
            val local = AcademicSession(AcademicSessionKey("plugin:$id", "default"), "https://invalid.example/")
            val native = NativeCapabilityHost(app, pkg, local, interaction) { true }
            try {
                val grant = native.execute(effect("academic.session.authorize", JSONObject()), NativeFlow(true)) as JSONObject
                assertFalse(grant.toString().contains(secret))
                server.enqueue(MockResponse().setBody("{\"name\":\"fixture\"}"))
                val response = native.execute(effect("academic.session.request", JSONObject().put("grant", grant.getString("grant")).put("request", request()), 2), NativeFlow(true)) as JSONObject
                assertFalse(response.toString().contains(secret))
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(secret, sent.getHeader("X-Token")); assertNull(sent.getHeader("Cookie")); assertNull(sent.getHeader("Authorization"))
            } finally { native.close(); local.retire() }
        }
    }
    @Test fun captchaFailureMalformedTokenAndStaleCaptureNeverPublish() {
        capture(result = "captcha"); assertNull(session.pluginToken)
        capture(status = 401); assertNull(session.pluginToken)
        for (token in listOf(JSONObject.NULL, 123, "", "bad token", "bad\r\n", "x".repeat(8193))) {
            val error = assertThrows(PluginException::class.java) { capture(JSONObject().put("data", JSONObject().put("token", token)).toString()) }
            assertEquals(PluginErrorCode.PAGE_CHANGED, error.code); assertNull(session.pluginToken)
        }
        val op = PluginOperation(session, source.manifest, "auth.start"); val capture = PluginAcademicTokenCapture(op, source)
        capture.capture(url("/jw/api/login"), "POST", "auth", 200, """{"data":{"token":"$secret"}}""")
        session.invalidate()
        assertThrows(PluginException::class.java) { capture.publish(JSONObject().put("status", "authenticated")) }; assertNull(session.pluginToken)
    }
    @Test fun wrongResponseOriginPathMethodOrPurposeDoesNotCaptureAndPointerEscapesWork() {
        val op = PluginOperation(session, source.manifest, "auth.start")
        val rule = PluginAcademicTokenRule(rule())
        val body = """{"data":{"token":"$secret"}}"""
        for (url in listOf(url("/jw/api/login-extra"), "https://other.test/jw/api/login".toHttpUrl()))
            assertNull(rule.capture(op, "owner", url, "POST", "auth", 200, body))
        assertNull(rule.capture(op, "owner", url("/jw/api/login"), "GET", "auth", 200, body))
        assertNull(rule.capture(op, "owner", url("/jw/api/login"), "POST", "query", 200, body))
        val spec = rule(); spec.getJSONObject("response").put("jsonPointer", "/a~1b/0/~0token")
        assertEquals(secret, PluginAcademicTokenRule(spec).capture(op, "owner", url("/jw/api/login"), "POST", "auth", 200,
            """{"a/b":[{"~token":"$secret"}]}""")!!.header(url("/jw/api/me")).second)
    }
    @Test fun scopeRedirectHeaderOverridesAndRevokedGrantsCannotSendToken() {
        capture(); val pkg = caller("test.scope"); val access = access(pkg); val grant = access.authorize().getString("grant"); val host = host(pkg, access, grant)
        for (req in listOf(request("/jw/api-evil/me"), request("/jw/other"), request().put("headers", JSONObject().put("x-token", "override")),
            request().put("headers", JSONObject().put("Authorization", "Bearer override")), request().put("cookieHeader", JSONObject().put("cookie", "token").put("header", "X-Token"))))
            assertThrows(PluginException::class.java) { host.call("http", req) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/jw/outside"))
        assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        server.takeRequest(1, TimeUnit.SECONDS); assertEquals(2, server.requestCount)
        PluginAcademicSession.revoke(app, pkg.manifest.id)
        assertThrows(PluginException::class.java) { host.call("http", request()) }; assertEquals(2, server.requestCount)
    }
    @Test fun expiredHttpResponseClearsTokenAndMissingTokenDoesNotFallBackToCookies() {
        capture(); val pkg = caller("test.expired"); val access = access(pkg); val grant = access.authorize().getString("grant"); val host = host(pkg, access, grant)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertNull(session.pluginToken)
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertEquals(2, server.requestCount)
    }
    @Test fun providerAccountEpochAndOwnerChangesPreventReuse() {
        capture(); val pkg = caller("test.isolation"); val access = access(pkg); val grant = access.authorize().getString("grant")
        val other = AcademicSession(AcademicSessionKey(school.id, "another-account"), school.fullBasePath); assertNull(other.pluginToken)
        session.pluginToken = PluginAcademicToken("other-provider", session.epoch, url("/jw/api"), secret)
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { access.tokenHeader(grant, url("/jw/api/me")) }.code)
        AcademicProviderRegistry.choose(school, "builtin.zf")
        assertEquals(PluginErrorCode.STALE_CONTEXT, assertThrows(PluginException::class.java) { access.tokenHeader(grant, url("/jw/api/me")) }.code)
        session.invalidate(); assertNull(session.pluginToken)
    }
    @Test fun declarationsRejectOldCapabilitiesNonAcademicWritersAndUnsafePaths() {
        for (path in listOf("//other.test", "/../api", "/a/./api", "/api%2f", "/api?token=x", "/api#x", "/a\\b")) {
            val spec = rule(); spec.getJSONObject("request").put("pathPrefix", path)
            assertThrows(PluginException::class.java) { PluginAcademicTokenRule(spec) }
        }
        val old = JSONObject(source.manifest.json.toString()).put("requires", JSONArray())
        assertThrows(PluginException::class.java) { PluginAcademicTokenRule.validate(PluginManifest(old)) }
        assertThrows(PluginException::class.java) { PluginAcademicTokenCapture(PluginOperation(session, source.manifest, "host.effect"), source) }
    }

    @Test fun allSevenAcademicTypesCanShareResponseTokens() {
        for (type in listOf("zf", "zf_old", "qz", "qz_old", "jinzhi", "chengfang", "legacy_zf")) {
            AcademicGatewayFactory.invalidate(school, UserManager.getInstance().currentAccountStorageKey)
            school.academicSystem = type
            source = install(JSONObject(source.manifest.json.toString()).put("id", "test.source-${type.replace('_', '-')}").put("school", schoolManifest()))
            AcademicProviderRegistry.choose(school, source.manifest.id)
            val user = UserManager.getInstance(); user.currentSchool = school; user.saveCookieLogin("fixture=login")
            session = AcademicGatewayFactory.sharedSession(school, user.currentAccountStorageKey)!!; session.cookies.clear()
            capture()
            val pkg = caller("test.reader-${type.replace('_', '-')}"); val access = access(pkg); val grant = access.authorize().getString("grant")
            server.enqueue(MockResponse().setBody(type))
            assertEquals(type, host(pkg, access, grant).call("http", request()).getJSONObject("data").getString("body"))
            assertEquals(secret, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
        }
    }

    @Test fun allSevenBuiltinInheritancePathsRetainTheSchoolTokenDeclaration() {
        for ((baseId, id) in BuiltinAcademicInheritance.providers) {
            val type = baseId.removePrefix("builtin.")
            val school = SchoolConfig("inherited", "Synthetic", "jw.example.test", "https").apply { basePath = "/new"; academicSystem = type }
            val json = JSONObject().put("id", "test.inherited").put("name", "Inherited").put("version", "1.0.0").put("kind", "configuration")
                .put("apiVersion", 3).put("extends", baseId).put("school", schoolManifest(school)).put("capabilities", JSONArray()).put("network", JSONArray())
                .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2))).put("academicSessionToken", rule())
            if (type == "jinzhi") json.put("builtinConfig", JSONObject().put("casBaseUrl", "https://cas.example.test/auth")
                .put("periods", JSONArray().put(JSONObject().put("number", 1).put("start", "08:00").put("end", "08:45"))))
            if (type == "chengfang") json.put("builtinConfig", JSONObject().put("loginUrl", "https://auth.example.test/login?service=https%3A%2F%2Fjw.example.test%2Fnew%2FssoLogin"))
            val parent = PluginPackage(PluginManifest(json), "", "parent", false)
            PluginAcademicTokenRule.validate(parent.manifest)
            val inherited = BuiltinAcademicInheritance.inherit(parent, AcademicProviderRegistry.knownPackage(id)!!, school)
            assertEquals(rule().toString(), inherited.manifest.json.getJSONObject("academicSessionToken").toString())
            assertTrue(PluginJson.objects(inherited.manifest.json.getJSONArray("requires")).any { it.getString("name") == "academic.session.request" && it.getInt("version") >= 2 })
        }
    }

    @Test fun versionOneAndOrdinaryNetworkDoNotAcquireTokenAndVersionTwoRequiresDeclaration() = runBlocking {
        capture()
        val pkg = caller("test.compatibility")
        val local = AcademicSession(AcademicSessionKey("plugin:${pkg.manifest.id}", "default"), "https://invalid.example/")
        val native = NativeCapabilityHost(app, pkg, local, interaction) { true }
        try {
            val grant = (native.execute(effect("academic.session.authorize", JSONObject()), NativeFlow(true)) as JSONObject).getString("grant")
            server.enqueue(MockResponse().setBody("old client"))
            native.execute(effect("academic.session.request", JSONObject().put("grant", grant).put("request", request()), 1), NativeFlow(true))
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
            server.enqueue(MockResponse().setBody("ordinary network"))
            native.execute(effect("network.request", request(), 1), NativeFlow(true))
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
            pkg.manifest.json.put("requires", JSONArray())
            try {
                native.execute(effect("academic.session.request", JSONObject().put("grant", grant).put("request", request()), 2), NativeFlow(true))
                fail("Version 2 needs an explicit capability requirement")
            } catch (e: PluginException) { assertEquals(PluginErrorCode.UNSUPPORTED, e.code) }
            assertEquals(3, server.requestCount)
        } finally { native.close(); local.retire() }
    }
}
