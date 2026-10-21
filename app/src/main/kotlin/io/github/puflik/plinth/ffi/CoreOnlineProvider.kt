package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.ffi.generated.ExternalCapabilities as RustCapabilities
import io.github.puflik.plinth.ffi.generated.ExternalFailure as RustFailure
import io.github.puflik.plinth.ffi.generated.ExternalProvider as RustProvider
import io.github.puflik.plinth.ffi.generated.ExternalResolve as RustResolve
import io.github.puflik.plinth.ffi.generated.ExternalSearch as RustSearch
import io.github.puflik.plinth.ffi.generated.ExternalStream as RustStream
import io.github.puflik.plinth.ffi.generated.NetHeader as RustNetHeader
import io.github.puflik.plinth.ffi.generated.OnlineKind as RustOnlineKind
import io.github.puflik.plinth.ffi.generated.OnlineResult as RustOnlineResult
import io.github.puflik.plinth.ffi.generated.QualityTier as RustQuality
import io.github.puflik.plinth.ffi.generated.StreamAddress as RustStreamAddress

/**
 * Провайдер, который живёт в приложении (Ю3в): YouTube на NewPipeExtractor. Ядро держит реестр, здоровье и
 * каталог, а провайдер отвечает на три вопроса — что найдётся, какие в этом треки, откуда играть. Сеть он ведёт
 * сам; что значит статус ответа, решает общая таблица ядра ([CoreHttpStatusTable]).
 *
 * Вызовы блокирующие. Беда — [ProviderException]; любое другое исключение ядро получает как «внутреннюю» беду
 * вызова с именем класса и больше ничем: сообщение исключения не берётся никогда — в нём бывают адрес и запрос.
 */
interface OnlineProvider {
    /** `[a-z0-9.-]{1,64}`; уходит в каталог и журнал пользователя и не меняется никогда. */
    val id: String

    /** Что даёт поиск; не пусто, иначе ядро провайдера не подключит. */
    val finds: Set<OnlineKind>

    /** Лучшее качество провайдера: по нему ядро ставит провайдеров по порядку. */
    val bestQuality: OnlineQuality

    /** Не больше [limit] результатов; идентификаторы идут в каталог дословно. */
    fun search(
        query: String,
        limit: Int,
    ): List<OnlineResult>

    /** Треки результата поиска: у собрания — все, у трека — он один; у каждого есть вариант. */
    fun resolve(item: String): List<OnlineTrack>

    /** Адрес и заголовки варианта [source] из [resolve]; каждый вызов даёт свежую ссылку. */
    fun stream(source: String): StreamAddress
}

/** Ступень качества провайдера — как `QualityTier` ядра. */
enum class OnlineQuality {
    STANDARD,
    HIGH_LOSSY,
    LOSSLESS,
}

/**
 * Беда провайдера — одна из трёх, как у ядра. [text] — всё, что о ней известно снаружи: имя класса сбоя или
 * решение общей таблицы статусов («http 503, retry after 7 s»). Адреса, запроса и названий в нём нет: он уходит
 * в ошибку ядра и в журнал. Исходное исключение — [cause], в журнал оно не идёт.
 */
sealed class ProviderException(
    val text: String,
    cause: Throwable? = null,
) : Exception(text, cause) {
    /** Нет ответа или сервер просит подождать: попробовать позже можно. */
    class Network(
        text: String,
        cause: Throwable? = null,
    ) : ProviderException(text, cause)

    /** Ответ пришёл, но формат не тот: сломался разбор, а не сеть. */
    class Parse(
        text: String,
        cause: Throwable? = null,
    ) : ProviderException(text, cause)

    /** Такого нет или оно закрыто: повтор не поможет. */
    class Unavailable(
        text: String,
        cause: Throwable? = null,
    ) : ProviderException(text, cause)
}

/**
 * Провайдер приложения как внешний провайдер ядра. Исключение через границу не пропускается: ядро ждёт ответ,
 * поэтому что бы ни случилось в провайдере, оно получает «найдено» или беду с видом и текстом. `id()` и
 * `capabilities()` не бросают: пустое имя и пустой `finds` ядро отвергнет понятной ошибкой подключения.
 */
internal fun OnlineProvider.toRust(): RustProvider =
    object : RustProvider {
        override fun id(): String = nameOrEmpty { this@toRust.id }

        override fun capabilities(): RustCapabilities =
            RustCapabilities(
                finds = nameOrEmpty(emptyList()) { this@toRust.finds.map(OnlineKind::toRust) },
                bestQuality = nameOrEmpty(RustQuality.STANDARD) { this@toRust.bestQuality.toRust() },
            )

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // Колбэк ядра не бросает: беда — в ответе.
        override fun search(
            query: String,
            limit: UInt,
        ): RustSearch =
            try {
                RustSearch.Found(this@toRust.search(query, limit.toInt()).map(OnlineResult::toRust))
            } catch (e: Exception) {
                RustSearch.Failed(e.toFailure())
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // Колбэк ядра не бросает: беда — в ответе.
        override fun resolve(item: String): RustResolve =
            try {
                RustResolve.Found(this@toRust.resolve(item).map(OnlineTrack::toRust))
            } catch (e: Exception) {
                RustResolve.Failed(e.toFailure())
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // Колбэк ядра не бросает: беда — в ответе.
        override fun stream(source: String): RustStream =
            try {
                RustStream.Found(this@toRust.stream(source).toRust())
            } catch (e: Exception) {
                RustStream.Failed(e.toFailure())
            }
    }

/** Что провайдер сообщил о себе; не смог (исключение) — [fallback]: ядро само откажет пустому имени и `finds`. */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // Колбэк ядра не бросает.
private inline fun <T> nameOrEmpty(
    fallback: T,
    ask: () -> T,
): T =
    try {
        ask()
    } catch (e: Exception) {
        fallback
    }

private inline fun nameOrEmpty(ask: () -> String): String = nameOrEmpty("", ask)

/** Вид беды и её текст; чужое исключение — только имя класса (у анонимного — двоичное имя без пакета). */
private fun Exception.toFailure(): RustFailure =
    when (this) {
        is ProviderException.Network -> RustFailure.Network(text)
        is ProviderException.Parse -> RustFailure.Parse(text)
        is ProviderException.Unavailable -> RustFailure.Unavailable(text)
        else -> RustFailure.Internal(javaClass.name.substringAfterLast('.'))
    }

internal fun OnlineResult.toRust() =
    RustOnlineResult(
        external = external,
        kind = kind.toRust(),
        title = title,
        artist = artist,
        year = year?.toUShort(),
        durationMs = duration?.inWholeMilliseconds?.toULong(),
    )

internal fun StreamAddress.toRust() =
    RustStreamAddress(
        url = url,
        headers = headers.map { (name, value) -> RustNetHeader(name, value) },
        // Нуль — это нуль, а не «нет срока»: ссылка, у которой срок вышел, не живёт.
        validForMs = validFor?.inWholeMilliseconds?.coerceAtLeast(0)?.toULong(),
    )

private fun OnlineKind.toRust() =
    when (this) {
        OnlineKind.TRACK -> RustOnlineKind.TRACK
        OnlineKind.ALBUM -> RustOnlineKind.ALBUM
    }

private fun OnlineQuality.toRust() =
    when (this) {
        OnlineQuality.STANDARD -> RustQuality.STANDARD
        OnlineQuality.HIGH_LOSSY -> RustQuality.HIGH_LOSSY
        OnlineQuality.LOSSLESS -> RustQuality.LOSSLESS
    }
