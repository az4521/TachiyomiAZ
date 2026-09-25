package eu.kanade.tachiyomi.data.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.ExifInterfaceImageHeaderParser
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Class used to update Glide module settings
 */
@GlideModule
class TachiGlideModule : AppGlideModule() {
    override fun applyOptions(
        context: Context,
        builder: GlideBuilder
    ) {
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, 50 * 1024 * 1024))
        builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
        builder.setDefaultTransitionOptions(
            Drawable::class.java,
            DrawableTransitionOptions.withCrossFade()
        )
    }

    override fun registerComponents(
        context: Context,
        glide: Glide,
        registry: Registry
    ) {
        // The decoders share this list, so the parser has to be swapped in place.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val parsers = registry.imageHeaderParsers
            parsers.forEachIndexed { i, parser ->
                if (parser is ExifInterfaceImageHeaderParser) {
                    parsers[i] = BoundedImageHeaderParser(parser)
                }
            }
        }

        val networkFactory = OkHttpUrlLoader.Factory(Injekt.get<NetworkHelper>().client)

        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            networkFactory
        )
        registry.append(
            MangaThumbnail::class.java,
            InputStream::class.java,
            MangaThumbnailModelLoader.Factory()
        )
        registry.append(
            InputStream::class.java,
            InputStream::class.java,
            PassthroughModelLoader.Factory()
        )

        registry.prepend(
            ByteBuffer::class.java,
            Bitmap::class.java,
            TachiyomiImageDecoderGlideWrapper.ByteBufferDecoder(glide.bitmapPool)
        )

        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            TachiyomiImageDecoderGlideWrapper.InputStreamDecoder(glide.bitmapPool)
        )
    }
}
