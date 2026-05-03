package io.legado.app.base

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.applyTint

@Suppress("MemberVisibilityCanBePrivate")
abstract class BaseFragment(@LayoutRes layoutID: Int) : Fragment(layoutID) {

    var supportToolbar: Toolbar? = null
        private set

    // 关键：安全上下文判断，防止未附着崩溃
    protected fun isFragmentValid(): Boolean {
        return isAdded && !isDetached && context != null && activity != null
    }

    val menuInflater: MenuInflater
        @SuppressLint("RestrictedApi")
        get() = SupportMenuInflater(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 先校验合法再执行后续逻辑
        if (!isFragmentValid()) return
        onMultiWindowModeChanged()
        observeLiveBus()
        onFragmentCreated(view, savedInstanceState)
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (!isFragmentValid()) return
        onMultiWindowModeChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isFragmentValid()) return
        onMultiWindowModeChanged()
    }

    private fun onMultiWindowModeChanged() {
        if (!isFragmentValid()) return
        (activity as? BaseActivity<*>)?.let {
            view?.findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(it.isInMultiWindow, it.fullScreen)
        }
    }

    fun setSupportToolbar(toolbar: Toolbar) {
        supportToolbar = toolbar
        if (!isFragmentValid()) return
        supportToolbar?.let {
            it.menu.apply {
                onCompatCreateOptionsMenu(this)
                applyTint(requireContext())
            }

            it.setOnMenuItemClickListener { item ->
                onCompatOptionsItemSelected(item)
                true
            }
        }
    }

    open fun observeLiveBus() {
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) {
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) {
    }

    
}