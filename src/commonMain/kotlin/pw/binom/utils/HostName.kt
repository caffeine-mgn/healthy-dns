package pw.binom.utils

import kotlin.jvm.JvmInline

@JvmInline
value class HostName(val raw: String) {
    val host
        get() = raw
    val isIpv4: Boolean
        get() {
            val items = raw.split('.')
            if (items.size != 4) {
                return false
            }
            return items.all {
                it.toIntOrNull()?.takeIf { it in 0..255 } != null
            }
        }
    val isIpv6: Boolean
        get() {
            if (raw.isBlank()) return false

            // 1. Быстрая отсечка по недопустимым символам
            if (!raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return false

            // 2. Проверка корректности использования "::" (компрессия нулей)
            val doubleColonCount = raw.split("::").size - 1
            if (doubleColonCount > 1) return false // "::" может встречаться только один раз

            val segments: List<String>
            if (doubleColonCount == 1) {
                // Разделяем адрес на левую и правую часть относительно "::"
                val parts = raw.split("::", limit = 2)
                val left = if (parts[0].isEmpty()) emptyList() else parts[0].split(':')
                val right = if (parts[1].isEmpty()) emptyList() else parts[1].split(':')

                // "::" обязана заменять минимум одну группу нулей
                if (left.size + right.size > 7) return false

                segments = left + right
            } else {
                // Полный формат: строго 8 групп
                segments = raw.split(':')
                if (segments.size != 8) return false
            }

            // 3. Валидация каждой группы: длина от 1 до 4 символов, только HEX
            return segments.all { segment ->
                segment.length in 1..4 && segment.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            }
        }
}