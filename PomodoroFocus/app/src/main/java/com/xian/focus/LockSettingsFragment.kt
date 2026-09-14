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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xian.focus.databinding.FragmentLockSettingsBinding

/**
 * 设置 → 锁机设置：锁机白名单、密码锁、锁机文案。
 *
 * 白名单跟底栏锁机页、番茄钟的自定义锁机弹窗用**同一份**存储（LockMachineController），
 * 在哪个入口改都立刻生效 —— 它是独立设置，不依附于任何一次锁机。
 *
 * 密码锁（[LockPin]）跟「打开贤要密码」（AppLockStore）是两件事：这一套只管
 * 退出锁机与应用限额加时，两个作用域各自一个开关（见 [PinScope]）。
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
        // 改密码：scope 传空 —— 只换密码，不动任何作用域开关
        binding.pinChangeRow.setOnClickListener { showSetPinDialog(null) }
        binding.quoteCard.setOnClickListener { showQuoteEditor() }
        binding.exitPinSwitch.setOnCheckedChangeListener(exitPinListener)
        binding.appLimitPinSwitch.setOnCheckedChangeListener(appLimitPinListener)

        refresh()
        binding.root.post { binding.root.staggerScrollContent() }
    }

    /**
     * 开关回调。只在用户真的拨动时才会走到这里：代码里同步状态走 [refresh] 的
     * 「先摘 listener 再赋值」那条路，不用 isPressed 之类的猜测。
     */
    private val exitPinListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        onScopeToggled(PinScope.EXIT_LOCK, checked)
    }

    private val appLimitPinListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        onScopeToggled(PinScope.APP_LIMIT, checked)
    }

    /**
     * 拨开某个作用域时若还没设过密码，先把密码设好 —— 只有开关没有密码等于没锁。
     * 对话框知道是哪个作用域拨的，设完由它把那个作用域打开，另一个保持原样。
     */
    private fun onScopeToggled(scope: PinScope, checked: Boolean) {
        val context = requireContext()
        when {
            !checked -> LockPin.setRequired(context, scope, false)
            LockPin.hasPin(context) -> LockPin.setRequired(context, scope, true)
            else -> showSetPinDialog(scope)
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
            if (LockPin.hasPin(context)) View.VISIBLE else View.GONE
        val quoteCount = LockQuotes.list(context).size
        binding.quoteSummary.text = if (quoteCount > 0) {
            getString(R.string.lock_settings_quotes_summary, quoteCount)
        } else {
            getString(R.string.lock_settings_quotes_desc)
        }
        // 先摘掉 listener 再赋状态，否则这次赋值会被当成「用户拨了开关」
        syncSwitch(binding.exitPinSwitch, PinScope.EXIT_LOCK, exitPinListener)
        syncSwitch(binding.appLimitPinSwitch, PinScope.APP_LIMIT, appLimitPinListener)
    }

    private fun syncSwitch(
        switch: SwitchCompat,
        scope: PinScope,
        listener: CompoundButton.OnCheckedChangeListener
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = LockPin.isRequired(requireContext(), scope)
        switch.setOnCheckedChangeListener(listener)
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

    /**
     * 文案编辑器：**一条一个输入框**，可以加、可以删。
     *
     * 首次打开时框里就是示例那几条 —— 用户改的是「已有的东西」，
     * 而不是面对一个空框不知道该写什么格式。存储格式没变（还是 `\n` 拼接的整段），
     * 变的只是编辑方式：一条条改比在一整块文本里数换行靠谱。
     */
    private fun showQuoteEditor() {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_quote_editor, null, false)
        fillQuoteRows(view, LockQuotes.list(context))
        view.findViewById<View>(R.id.quoteAddButton).setOnClickListener {
            addQuoteRow(view, "", focus = true)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.lock_quotes_editor_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                // 空框（加了没写）不落盘
                LockQuotes.save(context, collectQuoteRows(view).joinToString("\n"))
                Toast.makeText(context, R.string.lock_quotes_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.lock_quotes_reset, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (_binding != null) refresh() }
            .create()
        // 「恢复默认」只把示例摆回编辑框，不直接落盘 —— 用户可能只是想改其中一条
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                fillQuoteRows(view, LockQuotes.defaultList())
            }
        }
        dialog.show()
    }

    /** 按给定内容重建整列输入框（恢复默认也走这里）。 */
    private fun fillQuoteRows(root: View, quotes: List<String>) {
        val rows = root.findViewById<LinearLayout>(R.id.quoteRows)
        rows.removeAllViews()
        // 一条都没有时也得留一个空框，否则用户没有地方输入
        quotes.ifEmpty { listOf("") }.forEach { addQuoteRow(root, it) }
        updateQuoteCount(root)
    }

    private fun addQuoteRow(root: View, text: String, focus: Boolean = false) {
        val rows = root.findViewById<LinearLayout>(R.id.quoteRows)
        val row = layoutInflater.inflate(R.layout.item_quote_row, rows, false)
        val input = row.findViewById<EditText>(R.id.quoteRowInput)
        input.setText(text)
        input.setSelection(input.text.length)
        row.findViewById<View>(R.id.quoteRowDelete).setOnClickListener {
            if (rows.childCount == 1) {
                // 删到只剩这一条就清空它：编辑框里不能一个输入处都没有
                input.setText("")
            } else {
                rows.removeView(row)
            }
            updateQuoteCount(root)
        }
        rows.addView(row)
        updateQuoteCount(root)
        // 行多了新加的那条在可视区外，不滚到底用户会以为按钮没反应
        val scroll = root.findViewById<ScrollView>(R.id.quoteScroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (focus) input.requestFocus()
    }

    /** 顶部的「共 N 条」：只数有内容的，空框不算，免得用户以为空着也算一条。 */
    private fun updateQuoteCount(root: View) {
        root.findViewById<TextView>(R.id.quoteCountText).text =
            getString(R.string.lock_quotes_count, collectQuoteRows(root).size)
    }

    private fun collectQuoteRows(root: View): List<String> {
        val rows = root.findViewById<LinearLayout>(R.id.quoteRows)
        return (0 until rows.childCount).mapNotNull { index ->
            rows.getChildAt(index)
                .findViewById<EditText>(R.id.quoteRowInput)
                .text.toString().trim()
                .takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 设密码要输两遍：只输一遍的话，手滑设成了别的自己也发现不了，人就退不出锁机了。
     *
     * [scope] 是「用户为了哪个作用域才设的密码」，设完顺手打开它；改密码时传 null。
     */
    private fun showSetPinDialog(scope: PinScope?) {
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
            .setPositiveButton(R.string.save) { _, _ -> applyPin(first, second, scope) }
            .setNegativeButton(R.string.cancel, null)
            // 无论确定还是取消，开关状态都以真实存储为准
            .setOnDismissListener { if (_binding != null) refresh() }
            .show()
    }

    private fun applyPin(first: EditText, second: EditText, scope: PinScope?) {
        val context = requireContext()
        val pin = first.text.toString()
        when {
            pin.length != LockPin.PIN_LENGTH || !pin.all { it.isDigit() } ->
                Toast.makeText(context, R.string.exit_pin_invalid, Toast.LENGTH_SHORT).show()

            pin != second.text.toString() ->
                Toast.makeText(context, R.string.exit_pin_mismatch, Toast.LENGTH_SHORT).show()

            else -> {
                LockPin.setPin(context, pin)
                // 用户是为了这个作用域才设的密码，顺手把它打开；另一个保持原状
                scope?.let { LockPin.setRequired(context, it, true) }
                Toast.makeText(context, R.string.exit_pin_set_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun newPinInput(context: Context, hint: String) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        filters = arrayOf(InputFilter.LengthFilter(LockPin.PIN_LENGTH))
        this.hint = hint
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
