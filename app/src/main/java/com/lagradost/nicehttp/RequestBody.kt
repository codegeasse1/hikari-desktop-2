package com.lagradost.nicehttp

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

abstract class RequestBody {
    abstract fun toOkHttp(): okhttp3.RequestBody

    companion object {
        fun text(text: String, contentType: String = "text/plain;charset=utf-8"): RequestBody {
            val mediaType = contentType.toMediaTypeOrNull()
            return object : RequestBody() {
                override fun toOkHttp(): okhttp3.RequestBody = text.toRequestBody(mediaType)
            }
        }

        fun bytes(bytes: ByteArray, contentType: String = "application/octet-stream"): RequestBody {
            val mediaType = contentType.toMediaTypeOrNull()
            return object : RequestBody() {
                override fun toOkHttp(): okhttp3.RequestBody = bytes.toRequestBody(mediaType)
            }
        }

        fun form(params: Map<String, String>): RequestBody {
            return object : RequestBody() {
                override fun toOkHttp(): okhttp3.RequestBody {
                    val form = okhttp3.FormBody.Builder()
                    params.forEach { (k, v) -> form.addEncoded(k, v) }
                    return form.build()
                }
            }
        }
    }
}
