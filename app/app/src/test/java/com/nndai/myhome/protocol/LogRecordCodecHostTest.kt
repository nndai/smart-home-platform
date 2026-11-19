package com.nndai.myhome.protocol

import com.nndai.myhome.data.local.LogRecordCodec
import java.io.File

/** Standalone JVM check; no Android device or test dependency is needed. */
fun main(args: Array<String>) {
    val bytes = File(args.single()).readBytes()
    val decoded = LogRecordCodec.decode(bytes, 2, 1234, 7, true)
    check(decoded.records.size == 2 && decoded.records[0].text == "Đèn 🌧️")
    check(decoded.records[0].epoch == 0L && decoded.records[1].type == 2)
    check(decoded.committed == bytes.size)
    for (length in bytes.indices) {
        LogRecordCodec.decode(bytes.copyOf(length), 2, 1234, 7, false)
    }
    for (index in bytes.indices) {
        val corrupt = bytes.copyOf()
        corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
        check(runCatching { LogRecordCodec.decode(corrupt, 2, 1234, 7, true) }.isFailure)
    }
    check(runCatching { LogRecordCodec.decode(bytes, 1, 1234, 7, true) }.isFailure)
    check(runCatching { LogRecordCodec.decode(bytes, 2, 4321, 7, true) }.isFailure)
    check(runCatching { LogRecordCodec.decode(bytes, 2, 1234, 8, true) }.isFailure)
    println("C++/Kotlin UTF-8 framing, every split boundary, CRC corruption and identity checks passed")
}
