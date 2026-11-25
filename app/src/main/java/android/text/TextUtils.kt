package android.text

object TextUtils {
    @JvmStatic fun isEmpty(str: CharSequence?): Boolean = str == null || str.isEmpty()

    @JvmStatic fun join(delimiter: CharSequence, tokens: Iterable<*>): String = tokens.joinToString(delimiter)

    @JvmStatic fun join(delimiter: CharSequence, tokens: Array<*>): String = tokens.joinToString(delimiter)

    @JvmStatic fun equals(a: CharSequence?, b: CharSequence?): Boolean = a.toString() == b.toString()

    @JvmStatic fun isDigitsOnly(str: CharSequence?): Boolean =
        !str.isNullOrEmpty() && str.all { it.isDigit() }
}
