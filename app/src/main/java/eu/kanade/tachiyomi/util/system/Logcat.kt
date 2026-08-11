package eu.kanade.tachiyomi.util.system

import com.elvishew.xlog.XLog

/**
 * Minimal stand-in for Mihon's `logcat` helper so tracker code copied from upstream compiles
 * unchanged. Delegates to XLog, which is what the rest of this app uses.
 */
enum class LogPriority {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT
}

fun Any.logcat(
    priority: LogPriority = LogPriority.DEBUG,
    throwable: Throwable? = null,
    message: () -> String = { "" }
) {
    val tag = this::class.java.simpleName
    val text = message()
    val logger = XLog.tag(tag)
    when (priority) {
        LogPriority.VERBOSE -> if (throwable != null) logger.v(text, throwable) else logger.v(text)
        LogPriority.DEBUG -> if (throwable != null) logger.d(text, throwable) else logger.d(text)
        LogPriority.INFO -> if (throwable != null) logger.i(text, throwable) else logger.i(text)
        LogPriority.WARN -> if (throwable != null) logger.w(text, throwable) else logger.w(text)
        LogPriority.ERROR, LogPriority.ASSERT ->
            if (throwable != null) logger.e(text, throwable) else logger.e(text)
    }
}
