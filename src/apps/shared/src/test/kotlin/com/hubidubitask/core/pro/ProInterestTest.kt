package com.hubidubitask.core.pro

import com.hubidubitask.core.agent.Stub
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProInterestTest {
    private fun form(base: String) =
        ProInterest.Form("$base/forms/d/e/FORM/formResponse", "entry.11", "entry.22", "entry.33")

    @Test
    fun `one answer, with only the platform, version and language`() {
        Stub { 200 to "<html>recorded</html>" }.use { stub ->
            assertEquals(ProInterest.Result.SENT, ProInterest.send("android", "1.2.0", "pl", form(stub.base)))
            val call = stub.calls.single()
            assertEquals("POST", call.method)
            assertEquals("/forms/d/e/FORM/formResponse", call.path)
            assertNull(call.auth)
            val fields = call.body.split('&').associate {
                val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, "UTF-8")
            }
            assertEquals(mapOf("entry.11" to "android", "entry.22" to "1.2.0", "entry.33" to "pl"), fields)
        }
    }

    @Test
    fun `a form that refuses it, or no network, is just false`() {
        Stub { 400 to "" }.use { stub -> assertEquals(ProInterest.Result.FAILED, ProInterest.send("mac", "1.2.0", "en", form(stub.base))) }
        assertEquals(ProInterest.Result.FAILED, ProInterest.send("mac", "1.2.0", "en", form("http://127.0.0.1:1")))
    }

    @Test
    fun `no form set up, nothing sent`() {
        Stub { 200 to "" }.use { stub ->
            assertEquals(ProInterest.Result.NOT_OPEN, ProInterest.send("linux", "1.2.0", "de", ProInterest.Form("", "", "", "")))
            assertEquals(ProInterest.Result.NOT_OPEN, ProInterest.send("linux", "1.2.0", "de", form(stub.base).copy(platform = "")))
            assertTrue(stub.calls.isEmpty())
        }
    }

    @Test
    fun `a form without version and language questions gets the platform alone`() {
        Stub { 200 to "" }.use { stub ->
            val platformOnly = ProInterest.Form("${stub.base}/forms/d/e/FORM/formResponse", "entry.11")
            assertEquals(ProInterest.Result.SENT, ProInterest.send(ProInterest.ANDROID, "2.4.0", "pl", platformOnly))
            assertEquals("entry.11=Android", stub.calls.single().body)
        }
    }

    @Test
    fun `the owner's form is set up, and asks the platform`() {
        assertTrue(ProInterest.FORM.ready)
        assertTrue(ProInterest.FORM.url.endsWith("/formResponse"))
    }
}
