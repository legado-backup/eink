package io.legado.app.ui.book.read.page.delegate

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.util.Log
import android.view.Surface
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import java.lang.reflect.Method
import java.util.Random
import java.util.concurrent.Executors

object InkFastConfig {
    var fastTurnEnable: Boolean = true
}

/** 掌阅水波纹配置（关闭/慢速/中速/快速），读取自 SharedPreferences */
object InkRippleConfig {
    // "off" / "slow" / "medium" / "fast"
    var mode: String = "off"

    val enabled get() = mode != "off"

    fun load(ctx: Context) {
        mode = ctx.getPrefString("ink_ripple", "off") ?: "off"
    }
}

/** 掌阅 EPDCDevice PAGE_H 反射封装，非掌阅设备静默失败 */
object IReaderPageH {
    private const val TAG = "IReaderPageH"
    private const val FORCE_NEXT_PAGE_H = 0x01000063

    // 与 KOReader ireaderpageh 插件一致：方向按旋转映射，速度 慢=128 中=64 快=0
    private val NEXT_BY_ROTATION = intArrayOf(1, 4, 2, 3)
    private val PREV_BY_ROTATION = intArrayOf(2, 3, 1, 4)
    private val SPEED_BITS = mapOf("slow" to 128, "medium" to 64, "fast" to 0)

    private var postCommand: Method? = null
    private var postCommandNative: Method? = null
    private var forceNextMode: Method? = null
    private var initFailed = false

    private fun init() {
        if (postCommand != null || postCommandNative != null || initFailed) return
        runCatching {
            val c = Class.forName("android.eink.EPDCDevice")
            // Android 14 掌阅固件注册的是 (String, String[])，旧版是 (String)
            postCommandNative = runCatching {
                c.getMethod("nativePostCommand", String::class.java, Array<String>::class.java)
            }.getOrNull()
            postCommand = runCatching {
                c.getMethod("nativePostCommand", String::class.java)
            }.getOrNull()
            forceNextMode = c.getMethod("setForceNextPostMode", Int::class.java)
            if ((postCommand == null && postCommandNative == null) || forceNextMode == null) {
                initFailed = true
            }
        }.onFailure {
            Log.w(TAG, "EPDCDevice init failed: ${it.message}")
            initFailed = true
        }
    }

    /** 菜单显示用：打开设置菜单时现场探测 EPDCDevice 是否可用（每次重新探测，不缓存） */
    fun probe(): Boolean {
        return runCatching {
            val c = Class.forName("android.eink.EPDCDevice")
            val post = runCatching {
                c.getMethod("nativePostCommand", String::class.java, Array<String>::class.java)
            }.getOrNull() ?: runCatching {
                c.getMethod("nativePostCommand", String::class.java)
            }.getOrNull()
            val force = c.getMethod("setForceNextPostMode", Int::class.java)
            post != null && force != null
        }.getOrDefault(false)
    }

    /** 在 fillPage 提交新页面前调用，使该帧以 PAGE_H 水波纹刷新 */
    fun prepare(forward: Boolean, rotation: Int) {
        init()
        if (initFailed) return
        val speed = SPEED_BITS[InkRippleConfig.mode] ?: 128
        val dirIndex = rotation and 3
        val direction = if (forward) NEXT_BY_ROTATION[dirIndex] else PREV_BY_ROTATION[dirIndex]
        val effect = direction or speed
        runCatching {
            val cmd = "next-effect-type $effect"
            if (postCommandNative != null) {
                postCommandNative!!.invoke(null, cmd, null)
            } else {
                postCommand!!.invoke(null, cmd)
            }
            forceNextMode!!.invoke(null, FORCE_NEXT_PAGE_H)
            Log.i(TAG, "PAGE_H prepared, effect: $effect")
        }.onFailure {
            Log.w(TAG, "prepare failed: ${it.message}")
            initFailed = true
        }
    }
}

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {
    private val fastCmd = arrayOf("su","-c","resetprop","sys.eink.mode","14")
    private val cleanCmd = arrayOf("su","-c","resetprop","sys.eink.mode","10")
    private val mainHandler = Handler()
    private val pool = Executors.newSingleThreadExecutor()
    private val random = Random()

    private var turnCount = 0
    private val fullCountLimit = 15
    private val maxRand = 2147483547

    private fun exec(cmd: Array<String>) {
        runCatching { Runtime.getRuntime().exec(cmd).waitFor() }
    }

    override fun onAnimStart(animationSpeed: Int) {
        // 直接使用父类 context，读取配置
        InkFastConfig.fastTurnEnable = context.getPrefBoolean("ink_fast_turn", true)
        InkRippleConfig.load(context)
        val fastEnable = InkFastConfig.fastTurnEnable
        val rippleEnable = InkRippleConfig.enabled

        if (!fastEnable && !rippleEnable) {
            if (!isCancel) readView.fillPage(mDirection)
            stopScroll()
            return
        }

        // 水波纹开启时跳过 DU 快刷命令，避免覆盖 PAGE_H 动画
        if (!rippleEnable) {
            pool.submit { exec(fastCmd) }
            mainHandler.postDelayed({ pool.submit { exec(fastCmd) } }, 8)
        }

        turnCount++
        if (turnCount >= fullCountLimit) {
            val randNum = random.nextInt(maxRand + 1).toString()
            val fullCmd = arrayOf("su", "-c", "service call eink 5 s16 sys.eink.one_full_mode_timeline s16 $randNum")
            pool.submit { exec(fullCmd) }
            turnCount = 0
        }

        mainHandler.postDelayed({
            if (!isCancel) {
                if (rippleEnable) {
                    val forward = mDirection == PageDirection.NEXT
                    val rotation = readView.display?.rotation ?: Surface.ROTATION_0
                    IReaderPageH.prepare(forward, rotation)
                }
                readView.fillPage(mDirection)
            }
            stopScroll()
            if (!rippleEnable) {
                mainHandler.post { exec(cleanCmd) }
            }
        }, 35)
    }

    override fun onAnimStop() {}
    override fun setBitmap() {}
    override fun onDraw(canvas: Canvas) {}
}
