package com.yuno

import java.io.ByteArrayOutputStream

/** Flow's `ProtobufWriter`, the encoder behind YouTube's search `params` token. */
class ProtobufWriter {
    private val buffer = ByteArrayOutputStream(256)

    fun toByteArray(): ByteArray = buffer.toByteArray()

    fun writeVarint(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, WIRE_VARINT)
        writeRawVarint(value)
    }

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeVarint(fieldNumber, value.toLong())
    }

    fun writeBool(fieldNumber: Int, value: Boolean) {
        writeVarint(fieldNumber, if (value) 1L else 0L)
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
        writeRawVarint(value.size.toLong())
        buffer.write(value)
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeRawVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeRawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buffer.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        buffer.write(v.toInt() and 0x7F)
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_LENGTH_DELIMITED = 2

        fun encode(builder: ProtobufWriter.() -> Unit): ByteArray {
            val writer = ProtobufWriter()
            writer.builder()
            return writer.toByteArray()
        }
    }
}
