package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String {
        val enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        val padded = if (flags and NO_PADDING != 0) enc.withoutPadding() else enc
        val raw = padded.encodeToString(input)
        return if (flags and NO_WRAP != 0) raw.replace("\r", "").replace("\n", "") else raw
    }

    @JvmStatic fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
        encodeToString(input.copyOfRange(offset, offset + len), flags)

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic fun decode(str: String, flags: Int): ByteArray {
        val clean = str.replace("\\s".toRegex(), "")
        val dec = if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()
        return try {
            dec.decode(clean)
        } catch (_: IllegalArgumentException) {
            java.util.Base64.getDecoder().decode(clean)
        }
    }

    @JvmStatic fun decode(str: String, offset: Int, len: Int, flags: Int): ByteArray =
        decode(str.substring(offset, offset + len), flags)
}
