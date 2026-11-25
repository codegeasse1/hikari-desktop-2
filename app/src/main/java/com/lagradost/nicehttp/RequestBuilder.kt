package com.lagradost.nicehttp

import kotlin.time.Duration

class RequestBuilder internal constructor(private val requests: Requests) {
    private var _headers: Map<String, String>? = null
    var headers: Map<String, String>
        get() = _headers ?: requests.defaultHeaders
        set(value) {
            _headers = value
        }

    private var _referer: String? = null
    private var _refererSet = false
    var referer: String?
        get() = if (_refererSet) _referer else requests.defaultReferer
        set(value) {
            _referer = value
            _refererSet = true
        }

    private var _cookies: Map<String, String>? = null
    var cookies: Map<String, String>
        get() = _cookies ?: requests.defaultCookies
        set(value) {
            _cookies = value
        }

    private var _data: Map<String, String>? = null
    private var _dataSet = false
    var data: Map<String, String>?
        get() = if (_dataSet) _data else requests.defaultData
        set(value) {
            _data = value
            _dataSet = true
        }

    private var _responseParser: ResponseParser? = null
    private var _responseParserSet = false
    var responseParser: ResponseParser?
        get() = if (_responseParserSet) _responseParser else requests.responseParser
        set(value) {
            _responseParser = value
            _responseParserSet = true
        }

    private var _cacheTime: Duration? = null
    var cacheTime: Duration
        get() = _cacheTime ?: Duration.ZERO
        set(value) {
            _cacheTime = value
        }

    private var _timeout: Duration? = null
    var timeout: Duration
        get() = _timeout ?: Duration.ZERO
        set(value) {
            _timeout = value
        }

    var params: Map<String, String> = emptyMap()
    var allowRedirects: Boolean = true
    var interceptor: okhttp3.Interceptor? = null
    var verify: Boolean = true
    var files: List<NiceFile>? = null
    var json: Any? = null
    var requestBody: okhttp3.RequestBody? = null

    fun header(key: String, value: String) {
        _headers = ((_headers ?: requests.defaultHeaders) + (key to value))
    }

    fun param(key: String, value: String) {
        params = params + (key to value)
    }

    fun cookie(key: String, value: String) {
        _cookies = ((_cookies ?: requests.defaultCookies) + (key to value))
    }

    fun data(key: String, value: String) {
        _dataSet = true
        _data = ((_data ?: requests.defaultData) + (key to value))
    }

    companion object {
        operator fun invoke(requests: Requests, block: RequestBuilder.() -> Unit): RequestBuilder =
            RequestBuilder(requests).apply(block)
    }
}
