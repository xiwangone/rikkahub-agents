package me.rerere.rikkahub.data.vault

/**
 * bech32 编码（BIP-173）—— age 密钥文本格式的基础。
 *
 * 只实现编码与自洽校验（校验和 polymod 必须为 1 的定义性质），不做解码：
 * 本仓没有「从 bech32 文本读回密钥」的需求。
 */
internal object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** 编码：hrp + '1' + 5-bit 数据 + 6 字符校验和。 */
    fun encode(hrp: String, payload: ByteArray): String {
        val data = convertBits(payload)
        val polymod = polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1
        val checksum = (0 until 6).map { (polymod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** 校验一个 bech32 串是否自洽（polymod == 1）；编码器写错时这里会失败。 */
    fun verify(encoded: String): Boolean {
        val separator = encoded.lastIndexOf('1')
        if (separator < 1 || separator + 7 > encoded.length) return false
        val hrp = encoded.substring(0, separator)
        val values = encoded.substring(separator + 1).map { c -> CHARSET.indexOf(c.lowercaseChar()) }
        if (values.any { it < 0 }) return false
        return polymod(hrpExpand(hrp.lowercase()) + values) == 1
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) checksum = checksum xor GENERATOR[i]
            }
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }

    /** 8-bit 字节流 → 5-bit 数据组（末尾补零位，与 bech32 规范一致）。 */
    private fun convertBits(data: ByteArray): List<Int> {
        var accumulator = 0
        var bits = 0
        val out = ArrayList<Int>(data.size * 8 / 5 + 1)
        for (byte in data) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((accumulator ushr bits) and 31)
            }
        }
        if (bits > 0) out.add((accumulator shl (5 - bits)) and 31)
        return out
    }
}
