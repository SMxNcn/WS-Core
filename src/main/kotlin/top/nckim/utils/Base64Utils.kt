package top.nckim.utils

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs
import kotlin.random.Random

object Base64Utils {
    fun encodeWithOffset(input: String): String {
        val base64Encoded = Base64.getEncoder()
            .encodeToString(input.toByteArray(StandardCharsets.UTF_8))

        val offset = generateRandomOffset()

        val offsetEncoded = applyOffset(base64Encoded, offset)

        val hexPadding = buildString {
            repeat(4) {
                append(Random.nextInt(16).toString(16))
            }
        }

        val sign = if (offset > 0) "1" else "0"
        val offsetDigit = abs(offset).toString()

        return "::$offsetEncoded$hexPadding$sign$offsetDigit%]"
    }

    fun decodeWithOffset(encodedInput: String): String? {
        return try {
            if (!encodedInput.startsWith("::") || !encodedInput.endsWith("%]")) {
                return null
            }

            val content = encodedInput.substring(2, encodedInput.length - 2)

            if (content.length < 6) {
                return null
            }

            val offsetPart = content.substring(content.length - 2)
            val base64Part = content.dropLast(6)

            val sign = if (offsetPart[0] == '1') 1 else -1
            val offsetValue = offsetPart[1].digitToInt()
            val offset = sign * offsetValue

            val originalBase64 = applyOffset(base64Part, -offset)

            String(Base64.getDecoder().decode(originalBase64), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyOffset(base64Str: String, offset: Int): String = buildString {
        for (c in base64Str) {
            when {
                c.isLetter() -> {
                    val shifted = if (c.isLowerCase()) {
                        val newPos = ((c.code - 'a'.code + offset) % 26 + 26) % 26
                        ('A'.code + newPos).toChar()
                    } else {
                        val newPos = ((c.code - 'A'.code + offset) % 26 + 26) % 26
                        ('a'.code + newPos).toChar()
                    }
                    append(shifted)
                }
                c.isDigit() -> {
                    val newDigit = ((c.digitToInt() + offset) % 10 + 10) % 10
                    append(newDigit)
                }
                else -> append(c)
            }
        }
    }

    private fun generateRandomOffset(): Int {
        var offset: Int
        do {
            offset = Random.nextInt(19) - 9
        } while (offset == 0)
        return offset
    }
}