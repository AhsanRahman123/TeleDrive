package com.drdisagree.teledrive.core.media

import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.key.Keyer
import coil3.request.Options
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

@UnstableApi
val mediaModule = module {
    single {
        ImageLoader.Builder(androidContext())
            .components {
                addAnimatedImageDecoder()
                // SVG decode (vc181 B4): registered AFTER the animated decoder,
                // whose registration stays byte-identical to vc180. The claim sets
                // are disjoint — the animated factories claim only GIF / animated
                // WebP / HEIF headers, SvgDecoder only sources containing the
                // <svg marker — so neither path's precedence changes. List
                // thumbnails are unaffected: the fetcher below serves pre-rendered
                // JPEG bytes, which the SVG decoder never claims.
                add(SvgDecoder.Factory())
                add(ThumbnailFetcherFactory(get<ThumbnailStore>()))
                add(Keyer<ThumbnailModel> { data, _: Options -> thumbnailCacheKey(data.fileId) })
            }
            .crossfade(true)
            .build()
    }
    singleOf(::AndroidThumbnailStore) bind ThumbnailStore::class
    singleOf(::AndroidMediaMetadataExtractor) bind MediaMetadataExtractor::class
    factoryOf(::TelegramDataSourceFactory)
    single<ThumbnailMemoryCache> { CoilThumbnailMemoryCache(get()) }
    single { PipController(androidContext()) }
}
