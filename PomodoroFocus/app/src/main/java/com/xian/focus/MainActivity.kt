package com.xian.focus

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Toast
import java.io.File
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrognito.pinlockview.PinLockListener
import com.xian.focus.data.FocusRepository
import com.xian.focus.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val timerViewModel: TimerViewModel by viewModels()

    /** 冷启动清回收站里过期的东西用。 */
    @Inject
    lateinit var repository: FocusRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val lockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@MainActivity, R.string.lock_back_blocked, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeRes = try {
            ThemeStore.themeResId(ThemeStore.selectedTheme(this))
        } catch (_: Exception) {
            R.style.Theme_Xian_Qinglv
        }
        setTheme(themeRes)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadWallpaper()
        purgeExpiredTrash()

        onBackPressedDispatcher.addCallback(this, lockBackCallback)

        // 大屏留白。注册在第一个 Fragment 的视图创建之前才来得及生效。
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    applyLargeScreenPadding(v)
                }
            },
            false
        )

        // 二级页（便签 / 应用限额 / 倒数日 / 日记…）不该带底部导航栏：
        // 它属于「上一级」，而且 windowSoftInputMode 是 adjustResize —— 一打字键盘就把导航栏顶上来了。
        supportFragmentManager.addOnBackStackChangedListener {
            binding.bottomNavigation.visibility =
                if (supportFragmentManager.backStackEntryCount > 0) View.GONE else View.VISIBLE
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, TasksFragment())
                .commit()
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            // tab 顺序即导航方向：往右切从右侧进，往左切从左侧进
            when (item.itemId) {
                R.id.nav_timer -> showFragment(TimerFragment(), 0)
                R.id.nav_tasks -> showFragment(TasksFragment(), 1)
                R.id.nav_lock -> showFragment(LockFragment(), 2)
                R.id.nav_review -> showFragment(ReviewFragment(), 3)
                R.id.nav_profile -> showFragment(ProfileFragment(), 4)
                else -> false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener {
            // 当前页重复点击不重新创建 Fragment，也不播放切换动画。
        }

        observeLockState()
        requestNotificationPermissionIfNeeded()
        setupPinLock()
        if (AppLockStore.isEnabled(this)) showPinLock()
    }

    override fun onStart() {
        super.onStart()
        // 回到前台是最稳的拉起时机：Android 12+ 不允许从后台启动前台服务。
        // 应用内改设置时 Activity 不会重新走 onStart，所以那几处也各自喊了一次 sync。
        GuardService.sync(this)
    }

    /**
     * 大屏（平板 / 折叠屏展开态）给当前页面的根视图加左右留白，免得内容横铺成一整条。
     *
     * 为什么在代码里加，而不是在布局里给容器加 `padding`：
     * 1. 容器 padding 会把任务页的 DrawerLayout 一起内缩 —— 抽屉改从留白处滑出，
     *    而且屏幕最左边那条留白也不再响应侧滑手势（DrawerLayout 的边缘检测在自己范围内）；
     * 2. 写在布局里等于给手机也带上这个属性，说不清「到底改没改手机」。
     *
     * 手机侧 `page_side_padding` 是 0dp，第一行就返回，**一个 padding 都不设** ——
     * 所有布局文件（含横屏）保持原样，手机上的表现与改动前逐像素一致。
     *
     * DrawerLayout 本体同样不能内缩，所以只把留白加到它的内容子视图上
     * （抽屉自己那层用 layout_gravity 标着 START/END，跳过）。
     */
    private fun applyLargeScreenPadding(root: View) {
        val pad = resources.getDimensionPixelSize(R.dimen.page_side_padding)
        if (pad <= 0) return
        if (root !is DrawerLayout) {
            root.setPadding(
                pad + root.paddingLeft,
                root.paddingTop,
                pad + root.paddingRight,
                root.paddingBottom
            )
            return
        }
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val lp = child.layoutParams as? DrawerLayout.LayoutParams ?: continue
            if (lp.gravity == Gravity.START || lp.gravity == Gravity.END) continue
            child.setPadding(
                pad + child.paddingLeft,
                child.paddingTop,
                pad + child.paddingRight,
                child.paddingBottom
            )
        }
    }

    /**
     * 把自定义壁纸铺到窗口背景上。
     *
     * 老实现有两条硬伤，都在这里修掉：
     * 1. `BitmapFactory.decodeFile` 全尺寸解码，一张 4000×3000 的相机图就是 48MB 位图，
     *    还是在主线程、且没有 try/catch —— OOM 属于 Error，`catch (Exception)` 接不住，
     *    低内存机型上表现为「打开就闪退」。现在按屏幕像素采样，同一张图解出来约 12MB。
     * 2. 结果直接 `setBackgroundDrawable`，窗口背景默认 FILL ⇒ 不等比拉伸，
     *    横图铺到竖屏上明显变形。现在先按屏幕尺寸做等比 centerCrop，再交给窗口。
     */
    private fun loadWallpaper() {
        val path = WallpaperStore.getPath(this) ?: return
        val file = File(path)
        if (!file.exists()) return
        val bitmap = try {
            decodeWallpaper(file) ?: return
        } catch (t: Throwable) {
            // 壁纸坏掉最多是没壁纸，不该让应用起不来
            return
        }
        window.setBackgroundDrawable(BitmapDrawable(resources, bitmap))
    }

    private fun decodeWallpaper(file: File): Bitmap? {
        val metrics = resources.displayMetrics
        val targetW = metrics.widthPixels.coerceAtLeast(1)
        val targetH = metrics.heightPixels.coerceAtLeast(1)

        // 只读尺寸，不解码像素 —— 拿来算采样率
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        val source = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        // 一趟画进屏幕大小的位图：等比放大到两边都铺满，再居中裁掉多出来的部分。
        // 比 createScaledBitmap + createBitmap 两趟少一份中间位图（大图上就是几十 MB）。
        val cropped = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val scale = maxOf(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val dx = (targetW - source.width * scale) / 2f
        val dy = (targetH - source.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(cropped).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (cropped != source) source.recycle()
        return cropped
    }

    /**
     * 取「解码后总像素仍不小于屏幕像素」的最大 2 的幂降采样倍数。
     * 结果必然落在屏幕像素的 1~4 倍之间：既不会糊到看不出是张照片，也不会大得离谱。
     */
    private fun sampleSizeFor(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        val targetPixels = targetW.toLong() * targetH
        var sample = 1
        while (sample < 8 &&
            (width / (sample * 2)).toLong() * (height / (sample * 2)) >= targetPixels
        ) {
            sample *= 2
        }
        return sample
    }

    private fun setupPinLock() {
        binding.pinLockView.pinKeypad.attachIndicatorDots(binding.pinLockView.pinDots)
        binding.pinLockView.pinKeypad.setPinLockListener(object : PinLockListener {
            override fun onComplete(pin: String) {
                if (AppLockStore.checkPin(this@MainActivity, pin)) {
                    binding.pinLockView.root.visibility = View.GONE
                } else {
                    Toast.makeText(this@MainActivity, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                    binding.pinLockView.root.animate().translationX(20f).setDuration(60).withEndAction {
                        binding.pinLockView.root.translationX = 0f
                    }.start()
                    binding.pinLockView.pinKeypad.resetPinLockView()
                }
            }

            override fun onPinChange(pinLength: Int, intermediatePin: String?) = Unit
            override fun onEmpty() = Unit
        })
    }

    private fun showPinLock() {
        binding.pinLockView.pinKeypad.resetPinLockView()
        binding.pinLockView.root.visibility = View.VISIBLE
    }

    private fun observeLockState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    timerViewModel.timerState.collect { updateBackLock() }
                }
                launch {
                    timerViewModel.lockMode.collect { updateBackLock() }
                }
            }
        }
    }

    private fun updateBackLock() {
        lockBackCallback.isEnabled = timerViewModel.lockMode.value &&
            timerViewModel.timerState.value.status != TimerStatus.IDLE
    }

    /**
     * 回收站保留 30 天，到期在这里真删。
     *
     * 放在冷启动而不是挂定时任务：定时任务被 ROM 清掉就静默失效，而"用户不开应用"的
     * 那几天本来也不需要清理 —— 开应用的这一刻清一次就够了，还省一个后台唤醒。
     */
    private fun purgeExpiredTrash() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.purgeExpiredTrash(FocusRepository.TRASH_KEEP_MILLIS)
                    // 便贴不在数据库里（存在 prefs），得单独清一次
                    NoteStore.purgeExpired(
                        applicationContext,
                        System.currentTimeMillis() - FocusRepository.TRASH_KEEP_MILLIS
                    )
                }
            }
        }
    }

    /** 记住上一个 tab 序号，用来决定页面切换的方向 */
    private var lastTabIndex = 1

    private fun showFragment(
        fragment: androidx.fragment.app.Fragment,
        tabIndex: Int
    ): Boolean {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current?.javaClass == fragment.javaClass) {
            lastTabIndex = tabIndex
            return true
        }
        // 切 tab 要清掉二级页返回栈：否则从二级页直接点底部导航切走，
        // 返回键会把二级页翻回来，底部导航的显隐状态也跟着错。
        supportFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        val enter = if (tabIndex >= lastTabIndex) {
            R.anim.frag_enter_from_right
        } else {
            R.anim.frag_enter_from_left
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(enter, R.anim.frag_exit)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        lastTabIndex = tabIndex
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
