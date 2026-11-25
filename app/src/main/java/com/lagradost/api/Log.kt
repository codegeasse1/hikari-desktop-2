package com.lagradost.api

object Log {
    fun d(tag: String, message: String) {
        println("[D][$tag] $message")
    }

    fun i(tag: String, message: String) {
        println("[I][$tag] $message")
    }

    fun w(tag: String, message: String) {
        System.err.println("[W][$tag] $message")
    }

    fun e(tag: String, message: String) {
        System.err.println("[E][$tag] $message")
    }
}
