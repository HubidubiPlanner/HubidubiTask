package com.hubidubitask.core.pro

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * "Sign up for Pro". Nothing is sold yet: a press is one answer to the owner's own
 * Google Form, so the people who want Pro can be counted before it is built.
 * The answer says which platform, which version and which language, and
 * nothing else: no id, no cookie, nothing about the person or their items.
 */
object ProInterest {

    /** What a press came to: counted, sign-ups not open (no form yet), or not sent. */
    enum class Result { SENT, NOT_OPEN, FAILED }

    /** The form's address (up to `/formResponse`) and its three questions' entry names. */
    data class Form(val url: String, val platform: String, val version: String, val language: String) {
        val ready get() = listOf(url, platform, version, language).all { it.isNotBlank() }
    }

    /**
     * The owner's form, taken from its "Get pre-filled link". Blank until it
     * exists: the button is there, and nothing is sent.
     */
    val FORM = Form(url = "", platform = "", version = "", language = "")

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /**
     * One answer. [Result.NOT_OPEN] while there is no form, so the app never
     * blames the connection for it; [Result.FAILED] when the form didn't take
     * it or couldn't be reached. Throws nothing.
     */
    fun send(platform: String, version: String, language: String, form: Form = FORM,
             client: OkHttpClient = http): Result {
        if (!form.ready) return Result.NOT_OPEN
        val body = FormBody.Builder()
            .add(form.platform, platform)
            .add(form.version, version)
            .add(form.language, language)
            .build()
        val took = runCatching {
            client.newCall(Request.Builder().url(form.url).post(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        return if (took) Result.SENT else Result.FAILED
    }
}
