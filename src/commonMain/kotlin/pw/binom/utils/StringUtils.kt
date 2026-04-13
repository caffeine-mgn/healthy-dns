package pw.binom.utils

fun String.isWildcardMatch(wildcard: String): Boolean = wildcardMatch(this, wildcard)

@Suppress("NOTHING_TO_INLINE")
private inline operator fun String.invoke(index: Int) = if (index >= length) '\u0000' else this[index]

internal fun wildcardMatch(string: String, wildcard: String): Boolean {
    var text = 0
    var wild = 0

    var textBackup = -1
    var wildBackup = -1
    while (string.length != text) {
        when {
            wildcard(wild) == '*' -> {
                // new star-loop: backup positions in pattern and text
                textBackup = text
                wildBackup = ++wild
            }

            wildcard(wild) == '?' || wildcard(wild) == string(text) -> {
                // ? matched any character or we matched the current non-NUL character
                text++
                wild++
            }

            else -> {
                // if no stars we fail to match
                if (wildBackup == -1) {
                    return false
                }
                // star-loop: backtrack to the last * by restoring the backup positions
                // in the pattern and text
                text = ++textBackup
                wild = wildBackup
            }
        }
    }
    // ignore trailing stars
    while (wildcard(wild) == '*') {
        wild++
    }
    // at end of text means success if nothing else is left to match
    return wildcard.length == wild
}