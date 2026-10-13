package com.tvatlas.player.traffic

import okhttp3.*
import okio.*

/** Network interceptor: includes redirects/retries and partial reads, before gzip decompression. */
class TrafficInterceptor(private val store: TrafficStore, private val id: String,
    private val name: String, private val proxy: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        val counted = if (body == null) request else request.newBuilder().method(request.method, object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isDuplex() = body.isDuplex()
            override fun isOneShot() = body.isOneShot()
            override fun writeTo(sink: BufferedSink) {
                val output = object : ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        store.record(id, name, proxy, sent = byteCount)
                    }
                }.buffer()
                body.writeTo(output); output.flush()
            }
        }).build()
        val response = chain.proceed(counted)
        val incoming = response.body ?: return response
        val source = object : ForwardingSource(incoming.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) store.record(id, name, proxy, received = count)
                return count
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = incoming.contentType()
            override fun contentLength() = incoming.contentLength()
            override fun source() = source
        }).build()
    }
}
