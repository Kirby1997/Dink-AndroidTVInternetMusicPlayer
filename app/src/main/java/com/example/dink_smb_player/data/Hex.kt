package com.example.dink_smb_player.data

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex of these bytes via a lookup table — byte-identical to joining
 *  `"%02x".format(b)` per byte, without a String.format (and its Formatter) per byte. */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[b ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
    }
    return String(out)
}
