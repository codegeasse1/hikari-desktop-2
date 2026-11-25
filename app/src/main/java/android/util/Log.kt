package android.util

object Log {
    @JvmStatic fun v(tag: String, msg: String): Int {
        println("[V][$tag] $msg")
        return 0
    }

    @JvmStatic fun d(tag: String, msg: String): Int {
        println("[D][$tag] $msg")
        return 0
    }

    @JvmStatic fun i(tag: String, msg: String): Int {
        println("[I][$tag] $msg")
        return 0
    }

    @JvmStatic fun w(tag: String, msg: String): Int {
        System.err.println("[W][$tag] $msg")
        return 0
    }

    @JvmStatic fun e(tag: String, msg: String): Int {
        System.err.println("[E][$tag] $msg")
        return 0
    }

    @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int {
        println("[V][$tag] $msg\n$tr")
        return 0
    }

    @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int {
        println("[D][$tag] $msg\n$tr")
        return 0
    }

    @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int {
        println("[I][$tag] $msg\n$tr")
        return 0
    }

    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int {
        System.err.println("[W][$tag] $msg\n$tr")
        return 0
    }

    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int {
        System.err.println("[E][$tag] $msg")
        tr.printStackTrace()
        return 0
    }

    @JvmStatic fun getStackTraceString(tr: Throwable): String = tr.stackTraceToString()

    @JvmStatic fun println(priority: Int, tag: String, msg: String): Int {
        println("[$priority][$tag] $msg")
        return 0
    }
}
