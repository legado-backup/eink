package io.legado.app.utils

import android.content.Context

object EPDManager {
    private var mEinkManager: Any? = null
    private const val EINK_SERVICE = "eink"

    fun init(context: Context) {
        if (mEinkManager == null) {
            runCatching {
                mEinkManager = context.getSystemService(EINK_SERVICE)
            }
        }
    }

    fun setMode(mode: String) {
        runCatching {
            val method = mEinkManager?.javaClass?.getDeclaredMethod("setMode", String::class.java)
            method?.invoke(mEinkManager, mode)
        }
    }

    fun getMode(): String {
        return runCatching {
            val method = mEinkManager?.javaClass?.getDeclaredMethod("getMode")
            method?.invoke(mEinkManager) as? String ?: "12"
        }.getOrDefault("12")
    }

    fun release() {
        mEinkManager = null
    }

    object Mode {
        const val EPD_AUTO = "0"
        const val EPD_OVERLAY = "1"
        const val EPD_FULL_GC16 = "2"
        const val EPD_FULL_GL16 = "3"
        const val EPD_FULL_GLR16 = "4"
        const val EPD_FULL_GLD16 = "5"
        const val EPD_FULL_GCC16 = "6"
        const val EPD_PART_GC16 = "7"
        const val EPD_PART_GL16 = "8"
        const val EPD_PART_GLR16 = "9"
        const val EPD_PART_GLD16 = "10"
        const val EPD_PART_GCC16 = "11"
        const val EPD_A2 = "12"
        const val EPD_A2_DITHER = "13"
        const val EPD_DU = "14"
        const val EPD_DU4 = "15"
    }
}
