package com.lagradost.cloudstream3.plugins

import android.content.Context

@Suppress("unused")
@Target(AnnotationTarget.CLASS)
annotation class CloudstreamPlugin

/**
 * Legacy plugin base (pre-KMP era). Old .cs3 plugins extend this instead of
 * [BasePlugin] directly; both funnel into the same registry.
 */
abstract class Plugin : BasePlugin() {
    lateinit var activity: Context

    @Throws(Throwable::class)
    open fun load(context: Context) {
    }
}
