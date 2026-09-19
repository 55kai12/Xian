package com.xian.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xian.focus.databinding.FragmentWhitelistBinding
import kotlinx.coroutines.launch

/**
 * 白名单应用（二级页，从「设置 → 锁机设置」进入）。
 *
 * 应用按图标网格铺开：一行一个应用的条太占地方，4 列一屏能扫二十来个。
 * 点一下即选中，**立刻落盘** —— 白名单是独立设置，不依附于任何一次锁机，
 * 跟底栏锁机页、番茄钟自定义锁机弹窗、锁机层用的是同一份存储（LockMachineController）。
 * 落盘前统一过一遍 [WhitelistGate]：锁机进行中不给改，定时锁机开始前一小时要先过冷静期。
 */
class WhitelistFragment : Fragment() {

    private var _binding: FragmentWhitelistBinding? = null
    private val binding get() = _binding!!

    /** 闸门拦下时要把这一下勾退回去，所以留个引用。 */
    private var gridAdapter: AppGridAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWhitelistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.whitelistBackButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.whitelistGrid.layoutManager =
            GridLayoutManager(requireContext(), AppGridAdapter.SPAN)
        updateCount(LockMachineController.whitelist(requireContext()).size)

        // 扫一遍已装应用要几百毫秒（首次没有缓存时），先给个转圈，
        // 否则是一片空白，用户会以为白名单是空的。
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = WhitelistAppPicker.loadApps(requireContext())
            val target = _binding ?: return@launch
            val adapter = AppGridAdapter(
                apps,
                LockMachineController.whitelist(requireContext()),
                ::onPicked
            )
            gridAdapter = adapter
            target.whitelistLoading.visibility = View.GONE
            target.whitelistGrid.adapter = adapter
            target.whitelistGrid.visibility = View.VISIBLE
        }
    }

    /**
     * 网格是「点一下立刻落盘」，闸门也就架在这一下上 —— 拦下就把勾退回去，
     * 留着「看着选中、其实没存」的假状态比直接不改更让人困惑。
     */
    private fun onPicked(picked: Set<String>) {
        val context = requireContext()
        WhitelistGate.run(
            context,
            viewLifecycleOwner.lifecycleScope,
            onApply = {
                LockMachineController.saveWhitelist(context, picked)
                updateCount(picked.size)
            },
            onReject = { gridAdapter?.setSelection(LockMachineController.whitelist(context)) }
        )
    }

    private fun updateCount(count: Int) {
        binding.whitelistCountText.text = getString(R.string.whitelist_selected_count, count)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
