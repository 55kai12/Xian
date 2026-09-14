package com.xian.focus

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xian.focus.databinding.FragmentLockSettingsBinding

/**
 * 设置 → 锁机设置：锁机白名单 + 退出锁机的密码。
 *
 * 白名单跟底栏锁机页、番茄钟的自定义锁机弹窗用**同一份**存储（LockMachineController），
 * 在哪个入口改都立刻生效 —— 它是独立设置，不依附于任何一次锁机。
 * 退出密码跟「打开贤要密码」（AppLockStore）是两件事，见 [LockExitPin]。
 */
class LockSettingsFragment : Fragment() {

    private var _binding: FragmentLockSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLockSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lockSettingsBackButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.whitelistCard.setOnClickListener { showAppPicker() }
        binding.pinChangeRow.setOnClickListener { showSetPinDialog() }
        binding.exitPinSwitch.setOnCheckedChangeListener(exitPinListener)

        refresh()
        binding.root.post { binding.root.staggerScrollContent() }
    }

    /**
     * 开关回调。只在用户真的拨动时才会走到这里：代码里同步状态走 [refresh] 的
     * 「先摘 listener 再赋值」那条路，不用 isPressed 之类的猜测。
     */
    private val exitPinListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        val context = requireContext()
        when {
            !checked -> LockExitPin.setEnabled(context, false)
            // 开开关时若还没设过密码，先把密码设好 —— 只有开关没有密码等于没锁
            LockExitPin.hasPin(context) -> LockExitPin.setEnabled(context, true)
            else -> showSetPinDialog()
        }
        refresh()
    }

    private fun refresh() {
        val context = requireContext()
        val count = LockMachineController.whitelist(context).size
        binding.whitelistSummary.text = if (count > 0) {
            getString(R.string.lock_settings_whitelist_summary, count)
        } else {
            getString(R.string.lock_settings_whitelist_desc)
        }

        binding.pinChangeRow.visibility =
            if (LockExitPin.hasPin(context)) View.VISIBLE else View.GONE
        // 先摘掉 listener 再赋状态，否则这次赋值会被当成「用户拨了开关」
        binding.exitPinSwitch.setOnCheckedChangeListener(null)
        binding.exitPinSwitch.isChecked = LockExitPin.isEnabled(context)
        binding.exitPinSwitch.setOnCheckedChangeListener(exitPinListener)
    }

    private fun showAppPicker() {
        val selected = LockMachineController.whitelist(requireContext())
        WhitelistAppPicker.show(
            requireContext(),
            viewLifecycleOwner.lifecycleScope,
            selected
        ) { updated ->
            LockMachineController.saveWhitelist(requireContext(), updated)
            refresh()
        }
    }

    /** 设密码要输两遍：只输一遍的话，手滑设成了别的自己也发现不了，人就退不出锁机了。 */
    private fun showSetPinDialog() {
        val context = requireContext()
        val first = newPinInput(context, getString(R.string.exit_pin_new_hint))
        val second = newPinInput(context, getString(R.string.exit_pin_repeat_hint))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.exit_pin_set_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> applyPin(first, second) }
            .setNegativeButton(R.string.cancel, null)
            // 无论确定还是取消，开关状态都以真实存储为准
            .setOnDismissListener { if (_binding != null) refresh() }
            .show()
    }

    private fun applyPin(first: EditText, second: EditText) {
        val context = requireContext()
        val pin = first.text.toString()
        when {
            pin.length != LockExitPin.PIN_LENGTH || !pin.all { it.isDigit() } ->
                Toast.makeText(context, R.string.exit_pin_invalid, Toast.LENGTH_SHORT).show()

            pin != second.text.toString() ->
                Toast.makeText(context, R.string.exit_pin_mismatch, Toast.LENGTH_SHORT).show()

            else -> {
                LockExitPin.setPin(context, pin)
                Toast.makeText(context, R.string.exit_pin_set_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun newPinInput(context: Context, hint: String) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        filters = arrayOf(InputFilter.LengthFilter(LockExitPin.PIN_LENGTH))
        this.hint = hint
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
