package io.github.puflik.plinth.diagnostics.log

/**
 * Вырезает из текста лога то, что не должно попасть в публичный issue (G1.2 🔒):
 * адреса и URI (в `content://` бывает зашифрован путь), абсолютные пути
 * (остаётся расширение — оно помогает разбирать ошибки форматов),
 * строковые литералы SQL (через них в исключения попадают поисковые
 * запросы), токены и ключи, длинные непрозрачные строки, e-mail.
 *
 * Названия треков и то, что человек искал, по шаблону не распознать: код
 * такие строки в лог не передаёт, а имена файлов в путях и URI вырезаются
 * здесь. Лишнее вырезать не страшно — страшно пропустить.
 */
class LogRedactor {
    fun redact(text: String): String {
        var result = URI.replace(text) { "${it.groupValues[1]}://$REDACTED" }
        result = EMAIL.replace(result, "<email>")
        result = SQL_LITERAL.replace(result, "'$REDACTED'")
        result = KEY_VALUE.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$TOKEN" }
        result = BEARER.replace(result, "Bearer $TOKEN")
        result = PATH.replace(result) { "<path>" + EXTENSION.find(it.value.substringAfterLast('/'))?.value.orEmpty() }
        result = OPAQUE.replace(result, TOKEN)
        return result
    }

    private companion object {
        const val REDACTED = "<redacted>"
        const val TOKEN = "<token>"

        val URI = Regex("""\b([A-Za-z][A-Za-z0-9+.-]*)://[^\s"'<>]+""")
        val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

        // Литерал — после пробела, скобки, `=` или запятой и до такого же края: «don't» не задевается.
        val SQL_LITERAL = Regex("""(?<=[\s(=,])'[^']*'(?=[\s),;]|$)""")
        val KEY_VALUE =
            Regex(
                """(?i)\b(access_token|refresh_token|token|api[_-]?key|apikey|key|""" +
                    """secret|password|passwd|signature|sig)(\s*[=:]\s*)[^\s&;,]+""",
            )
        val BEARER = Regex("""(?i)\bBearer\s+[^\s;,]+""")

        // Абсолютный путь из двух и больше частей; `/` внутри имени модуля (`java.base/…`) — не путь.
        // Пробелы в именах папок и файлов бывают (ревью №6): путь идёт до `:`, кавычки, `<>` или конца строки —
        // лишнее захватить не страшно, отрезанный хвост пути страшно.
        val PATH = Regex("""(?<![\w.:/])/(?:[^\r\n\t/:'"<>]+/)+[^\r\n\t/:'"<>]*""")
        val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

        // Длинная строка из букв и цифр вперемешку: хэш, ключ, идентификатор сессии. Имена классов без цифр — не она.
        val OPAQUE = Regex("""\b(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}\b""")
    }
}
