package com.xian.focus

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import java.io.File
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrognito.pinlockview.PinLockListener
import com.xian.focus.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val timerViewModel: TimerViewModel by viewModels()

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

        onBackPressedDispatcher.addCallback(this, lockBackCallback)

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

    private fun loadWallpaper() {
        val path = WallpaperStore.getPath(this) ?: return
        val file = File(path)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(path) ?: return
        window.setBackgroundDrawable(BitmapDrawable(resources, bitmap))
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
