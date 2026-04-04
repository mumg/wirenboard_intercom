package net.muratov.intercom.logging

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object IntercomFileLogger {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var fileLoggingErrorReported = false
    @Volatile
    private var logFilePath: String? = null

    fun setLogFilePath(path: String) {
        logFilePath = path.takeIf { it.isNotBlank() }
        fileLoggingErrorReported = false
        Log.i("IntercomFileLogger", "Log file path set to $logFilePath")
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    @Synchronized
    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val fullMessage = buildString {
            append(message)
            if (throwable != null) {
                append(" | error=")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message)
            }
        }

        when (level) {
            "E" -> Log.e(tag, fullMessage, throwable)
            "W" -> Log.w(tag, fullMessage, throwable)
            "I" -> Log.i(tag, fullMessage)
            else -> Log.d(tag, fullMessage)
        }

        val line = buildString {
            append(LocalDateTime.now().format(timestampFormatter))
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(Log.getStackTraceString(throwable).trim())
            }
            append('\n')
        }

        val currentLogFilePath = logFilePath ?: return

        runCatching {
            val logFile = File(currentLogFilePath)
            logFile.parentFile?.mkdirs()
            logFile.appendText(line)
        }.onFailure { error ->
            if (!fileLoggingErrorReported) {
                fileLoggingErrorReported = true
                Log.e(tag, "Failed to write log file at $currentLogFilePath", error)
            }
        }
    }
}
