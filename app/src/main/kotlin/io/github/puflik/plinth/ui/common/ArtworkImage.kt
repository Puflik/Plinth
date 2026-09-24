package io.github.puflik.plinth.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.puflik.plinth.artwork.ArtworkLoader
import io.github.puflik.plinth.artwork.ArtworkSize

/**
 * Загрузчик обложек для экранов (E2); его кладёт `MainActivity`. Без него —
 * в превью и тестах экранов — вместо обложек всегда заглушка.
 */
val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader<ImageBitmap>?> { null }

/**
 * Обложка файла [uri] в размере [size], обрезанная по рамке; пока грузится и
 * если её нет — значок [placeholder] на нейтральном фоне. Картинка
 * декоративная: рядом всегда есть название, поэтому TalkBack её не читает.
 */
@Composable
fun ArtworkImage(
    uri: String?,
    size: ArtworkSize,
    @DrawableRes placeholder: Int,
    modifier: Modifier = Modifier,
) {
    val image = rememberArtwork(uri, size)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(placeholder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(PLACEHOLDER_SHARE),
            )
        }
    }
}

/**
 * Картинка сразу, если она уже в памяти, иначе — после загрузки. Сменился
 * файл — прежняя обложка не задерживается на экране ни кадра.
 */
@Composable
private fun rememberArtwork(
    uri: String?,
    size: ArtworkSize,
): ImageBitmap? {
    val loader = LocalArtworkLoader.current
    val image = remember(loader, uri, size) { mutableStateOf(uri?.let { loader?.peek(it, size) }) }
    LaunchedEffect(loader, uri, size) {
        if (loader != null && uri != null && image.value == null) image.value = loader.load(uri, size)
    }
    return image.value
}

/** Какую часть рамки занимает значок заглушки. */
private const val PLACEHOLDER_SHARE = 0.4f
