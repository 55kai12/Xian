package com.xian.focus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/**
 * 通知左侧那个方块里放的图。
 *
 * ⚠️ **不设 `setLargeIcon` 时，国产 ROM 会拿「应用图标」顶上** —— 而那份图由系统缓存，
 * 覆盖安装后经常不刷新：桌面已经是新图标，通知里还是旧的（v2.0.91 实测如此，
 * 采样通知里的旧图底色 `(254,255,250)` 近纯白，而当前包内图标底是 `#F6EFE3`）。
 * 显式给一张自己合成的图，通知就不再受系统图标缓存摆布。
 *
 * 合成方式与 adaptive icon 一致：底色铺满 + 前景内缩 1/6（`ic_launcher_foreground_inset.xml`
 * 的 18dp / 108dp）。所以通知里看到的就是桌面上那个图标。
 */
object NotifyIcon {

    /**
     * 通知大图按 64dp 显示，256px 覆盖 4x 密度还有富余；
     * 再大没意义，反而让 bitmap 跟着通知一起走 binder 传输、白白变重。
     */
    private const val SIZE = 256

    private var cached: Bitmap? = null

    fun large(context: Context): Bitmap {
        cached?.let { return it }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)?.let {
            it.setBounds(0, 0, SIZE, SIZE)
            it.draw(canvas)
        }
        val inset = SIZE / 6
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let {
            it.setBounds(inset, inset, SIZE - inset, SIZE - inset)
            it.draw(canvas)
        }
        cached = bitmap
        return bitmap
    }
}
