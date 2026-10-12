package com.dripagency.mlbbdraft.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads the 96 px portrait templates (downloaded set first, bundled assets second) with a small cache. */
object PortraitLoader {
    private val cache = LruCache<String, Bitmap>(160)

    suspend fun load(context: Context, heroId: String): Bitmap? {
        cache.get(heroId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val downloaded = File(context.filesDir, "templates/$heroId.png")
            val bmp = runCatching {
                if (downloaded.exists()) BitmapFactory.decodeFile(downloaded.path)
                else context.assets.open("templates/$heroId.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            bmp?.also { cache.put(heroId, it) }
        }
    }

    fun clear() = cache.evictAll()
}

@Composable
fun HeroAvatar(heroId: String, name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, heroId) { value = PortraitLoader.load(context, heroId) }
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Text(name.take(1), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (size.value * 0.4f).sp)
    }
}
