package com.jellywatch.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

class ArtworkLoader {
    private val client = OkHttpClient()
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(url: String?, imageView: ImageView) {
        imageView.tag = url
        if (url == null) return
        cache.get(url)?.let {
            imageView.imageTintList = null
            imageView.setImageBitmap(it)
            return
        }
        executor.execute {
            val bitmap = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.byteStream().use(BitmapFactory::decodeStream)
                }
            }.getOrNull()
            if (bitmap != null) {
                cache.put(url, bitmap)
                imageView.post {
                    if (imageView.tag == url) {
                        imageView.imageTintList = null
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
