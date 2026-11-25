package com.lagradost.nicehttp

interface Interceptor {
    suspend fun intercept(url: String, headers: Map<String, String>): Map<String, String> = headers
}
