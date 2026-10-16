package com.mermergi.ttsrelay.js

/**
 * 合成出来的音频**一块一块**往这儿送 —— 边合成边播。
 *
 * 以前只能等整句变成 byte[] 才开播；一句 40 字能吐 8~12 秒音频，那几秒就白等。
 * 现在插件吐一块就送一块，播放端（本机 HTTP 流 + MediaPlayer）同时已经在放了。
 *
 * 放在 Kotlin 这边是因为构建顺序：kotlinc 跑在 javac 前面，Java 里定义的接口
 * Kotlin 看不见（会 unresolved reference）。Java 侧照常实现它就行。
 */
fun interface AudioSink {
    @Throws(Exception::class)
    fun chunk(data: ByteArray, off: Int, len: Int)
}
