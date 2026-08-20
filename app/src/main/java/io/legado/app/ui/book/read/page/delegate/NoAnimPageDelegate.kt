package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.os.Handler
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.getPrefBoolean
import java.util.Random
import java.util.concurrent.Executors

object InkFastConfig {
    var fastTurnEnable: Boolean = true
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
        val fastEnable = InkFastConfig.fastTurnEnable

        if (!fastEnable) {
            if (!isCancel) readView.fillPage(mDirection)
            stopScroll()
            return
        }

        pool.submit { exec(fastCmd) }
        mainHandler.postDelayed({ pool.submit { exec(fastCmd) } }, 8)

        turnCount++
        if (turnCount >= fullCountLimit) {
            val randNum = random.nextInt(maxRand + 1).toString()
            val fullCmd = arrayOf("su", "-c", "service call eink 5 s16 sys.eink.one_full_mode_timeline s16 $randNum")
            pool.submit { exec(fullCmd) }
            turnCount = 0
        }

        mainHandler.postDelayed({
            if (!isCancel) readView.fillPage(mDirection)
            stopScroll()
            mainHandler.post { exec(cleanCmd) }
        }, 35)
    }

    override fun onAnimStop() {}
    override fun setBitmap() {}
    override fun onDraw(canvas: Canvas) {}
}