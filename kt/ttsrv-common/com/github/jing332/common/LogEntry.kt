package com.github.jing332.common

import android.graphics.Color

data class LogEntry(
    val level: Int,
    val message: String,
    val time: String = "",
    val wrapLine: Boolean = true
)
{
    fun getLevelChar(): String = level.toLogLevelChar()
}