package com.ridepack.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Internet link for riders over ntfy.sh (free, no account needed). Each ride code is a topic:
 * riders post packets to it and stream its messages. Anyone who knows the topic can read it.
 */
class OnlineLink(
    private val onPacket: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    @Volatile private var topic = ""
    @Volatile private var running = false
    @Volatile private var stream: Call? = null
    private val listenHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val postHttp = listenHttp.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    fun start(code: String) {
        stop()
        topic = "$PREFIX${code.lowercase()}"
        running = true
        Thread { listen() }.apply { isDaemon = true; start() }
    }

    private fun listen() {
        while (running) {
            try {
                val call = listenHttp.newCall(Request.Builder().url("$BASE/$topic/json").build())
                stream = call
                call.execute().use { resp ->
                    val src = resp.body?.source() ?: return@use
                    while (running && !src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        try {
                            val o = JSONObject(line)
                            when (o.optString("event")) {
                                "open" -> onState(true)
                                "message" -> onPacket(o.getString("message"))
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
            if (!running) break
            onState(false)
            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
        }
        onState(false)
    }

    /** Posts one packet. Keep the send rate low: ntfy.sh limits how many messages a client may post. */
    fun send(s: String) {
        val t = topic
        if (t.isEmpty() || !running) return
        val body = s.toRequestBody("text/plain; charset=utf-8".toMediaType())
        postHttp.newCall(Request.Builder().url("$BASE/$t").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun stop() {
        running = false
        topic = ""
        stream?.cancel()
        stream = null
    }

    companion object {
        /** Public ntfy server. To self-host, point this at your own server. */
        const val BASE = "https://ntfy.sh"
        const val PREFIX = "ridepack-"
    }
}
