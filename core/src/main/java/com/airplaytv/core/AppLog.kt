package com.airplaytv.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leveled logger. Everything goes to logcat; WARN and above are also kept in a small
 * in-memory ring buffer so the "Error log" screen can show them. Nothing is written to
 * disk and nothing leaves the device. Never log PINs, passwords or keys.
 */
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val time: Long, val level: Level, val tag: String, val message: String) {
        fun formatted(): String =
            "${TIME_FORMAT.get()!!.format(Date(time))} ${level.name.first()} $tag: $message"
    }

    private const val CAPACITY = 300
    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    }

    @Volatile var minLevel: Level = Level.INFO
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        if (level < minLevel) return
        val full = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg
        when (level) {
            Level.DEBUG -> Log.d(tag, full)
            Level.INFO -> Log.i(tag, full)
            Level.WARN -> Log.w(tag, full, t)
            Level.ERROR -> Log.e(tag, full, t)
        }
        if (level >= Level.WARN) {
            synchronized(buffer) {
                if (buffer.size == CAPACITY) buffer.removeFirst()
                buffer.addLast(Entry(System.currentTimeMillis(), level, tag, full))
                _entries.value = buffer.toList()
            }
        }
    }

    fun clear() = synchronized(buffer) {
        buffer.clear()
        _entries.value = emptyList()
    }
}
