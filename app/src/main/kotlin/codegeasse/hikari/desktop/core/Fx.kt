package codegeasse.hikari.desktop.core

import javafx.application.Platform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** JavaFX thread bridge. The single rule: never block the FX thread. */
object Fx {
    @Volatile private var fxThread: Thread? = null

    fun onStart() {
        fxThread = Thread.currentThread()
    }

    fun isFx(): Boolean = Platform.isFxApplicationThread()

    fun run(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) {
            runCatching { block() }
        } else {
            Platform.runLater { runCatching { block() } }
        }
    }

    /**
     * Run on FX and wait (for BACKGROUND threads only). Times out fast so a
     * wedged FX thread fails the caller instead of freezing a pool thread.
     */
    fun <T> runBlock(timeoutMs: Long = 5_000L, block: () -> T): T {
        if (Platform.isFxApplicationThread()) return block()
        val latch = CountDownLatch(1)
        val res = AtomicReference<Result<T>>()
        Platform.runLater {
            res.set(runCatching { block() })
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException("Fx.runBlock timed out after ${timeoutMs}ms")
        }
        return res.get().getOrThrow()
    }
}
