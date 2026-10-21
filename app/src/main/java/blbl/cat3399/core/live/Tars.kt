package blbl.cat3399.core.live

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Tars 二进制编解码（移植自 PureLive `live_core/src/tars.dart`）。
 *
 * 虎牙的弹幕协议（wss://wsapi.huya.com ）与 WUP 令牌请求都使用 Tars 编码：
 * 每个字段由「tag(4bit) + type(4bit)」头部引导，tag >= 15 时后跟一个字节的 tag。
 *
 * 这里只实现本项目用到的子集：int8/16/32/64、string1/4、simpleList(byte[])、list、struct。
 * 读取结果以 `Map<Int, Any?>`（tag -> value）表示，整数为 [Long]，字符串为 [String]，
 * 字节数组为 [ByteArray]，嵌套结构为 `Map<Int, Any?>`，列表为 `List<Any?>`。
 */
internal object Tars {
    private const val TYPE_INT8 = 0
    private const val TYPE_INT16 = 1
    private const val TYPE_INT32 = 2
    private const val TYPE_INT64 = 3
    private const val TYPE_FLOAT32 = 4
    private const val TYPE_FLOAT64 = 5
    private const val TYPE_STRING1 = 6
    private const val TYPE_STRING4 = 7
    private const val TYPE_LIST = 9
    private const val TYPE_STRUCT_BEGIN = 10
    private const val TYPE_STRUCT_END = 11
    private const val TYPE_ZERO = 12
    private const val TYPE_SIMPLE_LIST = 13

    // ---------------------------------------------------------------- 写入

    internal class Writer {
        private val out = ByteArrayOutputStream()

        private fun head(type: Int, tag: Int) {
            require(tag in 0..255) { "tars tag out of range: $tag" }
            if (tag < 15) {
                out.write((tag shl 4) or type)
            } else {
                out.write(0xF0 or type)
                out.write(tag)
            }
        }

        fun writeInt(tag: Int, value: Long) {
            when {
                value == 0L -> head(TYPE_ZERO, tag)
                value in -128..127 -> {
                    head(TYPE_INT8, tag)
                    out.write((value and 0xFF).toInt())
                }
                value in -32768..32767 -> {
                    head(TYPE_INT16, tag)
                    out.write((value shr 8).toInt())
                    out.write(value.toInt())
                }
                value in Int.MIN_VALUE..Int.MAX_VALUE -> {
                    head(TYPE_INT32, tag)
                    writeInt32(value.toInt())
                }
                else -> {
                    head(TYPE_INT64, tag)
                    val buf = ByteBuffer.allocate(8)
                    buf.putLong(value)
                    out.write(buf.array())
                }
            }
        }

        private fun writeInt32(value: Int) {
            out.write((value shr 24) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeString(tag: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > 255) {
                head(TYPE_STRING4, tag)
                writeInt32(bytes.size)
            } else {
                head(TYPE_STRING1, tag)
                out.write(bytes.size)
            }
            out.write(bytes)
        }

        fun writeBytes(tag: Int, value: ByteArray) {
            head(TYPE_SIMPLE_LIST, tag)
            head(TYPE_INT8, 0)
            writeInt(0, value.size.toLong())
            out.write(value)
        }

        fun writeStringList(tag: Int, items: List<String>) {
            head(TYPE_LIST, tag)
            writeInt(0, items.size.toLong())
            for (item in items) writeString(0, item)
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    // ---------------------------------------------------------------- 读取

    private class Reader(
        private val bytes: ByteArray,
        private var pos: Int = 0,
    ) {
        fun readFields(): Map<Int, Any?> {
            val fields = HashMap<Int, Any?>()
            while (pos < bytes.size) {
                val head = bytes[pos].toInt() and 0xFF
                val type = head and 0x0F
                var tag = (head and 0xF0) shr 4
                pos++
                if (tag == 15) {
                    if (pos >= bytes.size) break
                    tag = bytes[pos].toInt() and 0xFF
                    pos++
                }
                if (type == TYPE_STRUCT_END) break
                fields[tag] = readValue(type)
            }
            return fields
        }

        private fun readValue(type: Int): Any? =
            when (type) {
                TYPE_ZERO -> 0L
                TYPE_INT8 -> bytes[pos++].toLong()
                TYPE_INT16 -> {
                    val v = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                    pos += 2
                    v.toShort().toLong()
                }
                TYPE_INT32 -> {
                    val v =
                        ((bytes[pos].toInt() and 0xFF) shl 24) or
                            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                            (bytes[pos + 3].toInt() and 0xFF)
                    pos += 4
                    v.toLong()
                }
                TYPE_INT64 -> {
                    val buf = ByteBuffer.wrap(bytes, pos, 8)
                    pos += 8
                    buf.long
                }
                TYPE_FLOAT32 -> {
                    val v = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).float
                    pos += 4
                    v.toDouble()
                }
                TYPE_FLOAT64 -> {
                    val v = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN).double
                    pos += 8
                    v
                }
                TYPE_STRING1 -> readString(readLength(1))
                TYPE_STRING4 -> readString(readLength(4))
                TYPE_SIMPLE_LIST -> {
                    // 本项目只用 byte[]（元素为 int8）：元素头之后是完整编码的长度整数字段。
                    val head = bytes[pos].toInt() and 0xFF
                    pos++
                    require((head and 0x0F) == TYPE_INT8) { "tars simple list element is not int8" }
                    val len = readIntValue().toInt()
                    val start = pos
                    pos += len
                    bytes.copyOfRange(start, start + len)
                }
                TYPE_LIST -> {
                    val len = readIntValue().toInt()
                    val items = ArrayList<Any?>(len)
                    // 列表元素依次排列；用元素的头部判断类型，struct 元素读到 STRUCT_END 为止。
                    for (i in 0 until len) {
                        if (pos >= bytes.size) break
                        val head = bytes[pos].toInt() and 0xFF
                        val elemType = head and 0x0F
                        var tag = (head and 0xF0) shr 4
                        pos++
                        if (tag == 15) {
                            tag = bytes[pos].toInt() and 0xFF
                            pos++
                        }
                        items.add(readValue(elemType))
                    }
                    items
                }
                TYPE_STRUCT_BEGIN -> readFields()
                else -> error("tars unsupported type $type")
            }

        private fun readLength(lenBytes: Int): Int {
            val v =
                when (lenBytes) {
                    1 -> bytes[pos].toInt() and 0xFF
                    2 -> ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                    4 ->
                        ((bytes[pos].toInt() and 0xFF) shl 24) or
                            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                            (bytes[pos + 3].toInt() and 0xFF)
                    else -> readIntValue().toInt()
                }
            pos += lenBytes
            return v
        }

        /** 读取一个完整的整数值（头部 + 数据），用于 list/simpleList 的长度前缀。 */
        private fun readIntValue(): Long {
            val head = bytes[pos].toInt() and 0xFF
            val type = head and 0x0F
            var tag = (head and 0xF0) shr 4
            pos++
            if (tag == 15) {
                tag = bytes[pos].toInt() and 0xFF
                pos++
            }
            return readValue(type) as? Long ?: 0L
        }

        private fun readString(len: Int): String {
            val s = String(bytes, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }
    }

    fun decode(bytes: ByteArray): Map<Int, Any?> = Reader(bytes).readFields()

    // ---------------------------------------------------------------- 访问器

    fun int(fields: Map<Int, Any?>?, tag: Int): Long? = (fields?.get(tag) as? Long)

    fun string(fields: Map<Int, Any?>?, tag: Int): String? = (fields?.get(tag) as? String)

    fun bytes(fields: Map<Int, Any?>?, tag: Int): ByteArray? = (fields?.get(tag) as? ByteArray)

    fun struct(fields: Map<Int, Any?>?, tag: Int): Map<Int, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return fields?.get(tag) as? Map<Int, Any?>
    }

    fun list(fields: Map<Int, Any?>?, tag: Int): List<Any?>? {
        @Suppress("UNCHECKED_CAST")
        return fields?.get(tag) as? List<Any?>
    }
}
