package com.xian.focus

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.xian.focus.databinding.FragmentNoteBinding

/**
 * 灵感便贴：一叠可以翻的便签，随手写、随手存。
 * 入口在任务清单左上角抽屉里。
 *
 * 便签没有单独的删除入口 —— 内容清空再往下翻，这一张就自动丢掉（只剩一张时保留）。
 */
class NoteFragment : Fragment() {

    private var _binding: FragmentNoteBinding? = null
    private val binding get() = _binding!!

    private val notes = mutableListOf<NoteStore.Note>()

    /** 当前看的是哪一张，用 createdAt 当 id。 */
    private var currentId: Long = 0L

    /** setText 回填时不要当成用户输入再存一遍。 */
    private var rendering = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.post { binding.root.staggerScrollContent() }
        binding.root.post { squareCard() }
        binding.noteBackButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.notePrevButton.setOnClickListener { switchNote(-1) }
        binding.noteNextButton.setOnClickListener { switchNote(1) }
        binding.noteStarButton.setOnClickListener { toggleStar() }
        binding.noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                notes.find { it.createdAt == currentId }?.text = s?.toString().orEmpty()
                NoteStore.save(requireContext(), notes)
            }
        })

        notes += NoteStore.load(requireContext())
        if (notes.isEmpty()) notes += newNote()
        // 打开先看最近写下的那一张
        currentId = notes.maxByOrNull { it.createdAt }!!.createdAt
        renderNote()
    }

    override fun onPause() {
        super.onPause()
        commitCurrent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 便签纸做成正方形（截图那样的方卡片）；空间不够就顶到能用的高度为止。 */
    private fun squareCard() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val size = minOf(binding.noteCard.width, binding.noteStackArea.height - dp(18 + 50))
        if (size <= 0) return
        binding.noteCard.layoutParams = binding.noteCard.layoutParams.apply { height = size }
    }

    private fun newNote() = NoteStore.Note(System.currentTimeMillis(), "", false)

    private fun renderNote() {
        val note = notes.find { it.createdAt == currentId } ?: return
        rendering = true
        binding.noteInput.setText(note.text)
        binding.noteInput.setSelection(note.text.length)
        rendering = false
        val starred = ContextCompat.getDrawable(
            requireContext(),
            if (note.starred) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        val tint = if (note.starred) {
            requireContext().themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
        } else {
            ContextCompat.getColor(requireContext(), R.color.text_tertiary)
        }
        starred?.mutate()?.setTint(tint)
        binding.noteStarButton.setImageDrawable(starred)
    }

    /** 翻页：-1 上一张（更早），+1 下一张；已经在最后一页还往下翻就换一张新的空白便签。 */
    private fun switchNote(step: Int) {
        commitCurrent()
        val list = NoteStore.ordered(notes)
        val index = list.indexOfFirst { it.createdAt == currentId }
        if (index < 0) {
            // 当前这张刚被清空丢掉了，直接换一张新的
            currentId = newNote().also { notes += it }.createdAt
        } else {
            val target = index + step
            if (target < 0) return
            if (target >= list.size) {
                if (notes.find { it.createdAt == currentId }?.text.isNullOrBlank()) return
                currentId = newNote().also { notes += it }.createdAt
            } else {
                currentId = list[target].createdAt
            }
        }
        renderNote()
        NoteStore.save(requireContext(), notes)
    }

    private fun toggleStar() {
        val note = notes.find { it.createdAt == currentId } ?: return
        note.starred = !note.starred
        renderNote()
        NoteStore.save(requireContext(), notes)
    }

    private fun commitCurrent() {
        val note = notes.find { it.createdAt == currentId } ?: return
        val text = binding.noteInput.text?.toString().orEmpty()
        if (text.isBlank() && notes.size > 1) {
            notes.remove(note)
        } else {
            note.text = text
        }
        NoteStore.save(requireContext(), notes)
    }
}
