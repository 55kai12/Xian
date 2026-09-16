package com.xian.focus

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.xian.focus.databinding.FragmentNoteBinding
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 灵感便贴：一叠可以翻的便签，随手写、随手存。入口在任务清单左上角抽屉里。
 *
 * 手势（判定都在 [NoteSwipeLayout] 里）：
 * - 上下滑动翻页：往下拖看更早的，往上拖看更新的；最后一页再往上拖就新开一张
 * - 长按便签拖到垃圾桶，松手即删
 * 点标题可以看全部便贴，直接跳过去。
 */
class NoteFragment : Fragment() {

    private var _binding: FragmentNoteBinding? = null
    private val binding get() = _binding!!

    private val notes = mutableListOf<NoteStore.Note>()

    /** 当前看的是哪一张，用 createdAt 当 id。 */
    private var currentId: Long = 0L

    /** setText 回填时不要当成用户输入再存一遍。 */
    private var rendering = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressAction: Runnable? = null

    /** 长按回调里拿不到坐标，只能在按下时先记着。 */
    private var downRawX = 0f
    private var downRawY = 0f

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
        // 便签纸尺寸必须跟着可用空间走：键盘弹起、底部导航栏隐藏、转屏、
        // 换一台屏幕比例不同的手机，这块区域的高宽都会变 —— 只在 onViewCreated 里算一次迟早对不上。
        binding.noteSwipeLayout.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) squareCard()
        }
        binding.root.post { squareCard() }
        binding.noteBackButton.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.noteTitleBlock.setOnClickListener { showNoteList() }
        binding.noteStarButton.setOnClickListener { toggleStar() }
        setupGestures()
        binding.noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                notes.find { it.createdAt == currentId }?.text = s?.toString().orEmpty()
                NoteStore.save(requireContext(), notes)
                // 字数要跟着敲字实时涨，但没必要为此重画整张便贴
                updateMeta()
            }
        })

        // 视图重建（配置变更 / 从返回栈回来）会再走一遍 onViewCreated，先清空免得攒成重复项
        notes.clear()
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
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    /**
     * 便签纸做成正方形（截图那样的方卡片），边长取「可用宽度」和「可用高度」里小的那个：
     * 宽屏受高度限制、窄屏受宽度限制，键盘弹起时自动缩小。
     * 这样任何屏宽/屏高比例下都不会被挤变形或溢出，不用按机型调。
     *
     * 左右和顶部留白直接读 noteSheet 的 layoutParams —— 以后改布局不用回来同步这里的数字。
     */
    private fun squareCard() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val parent = binding.noteSheet.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val sheetParams = binding.noteSheet.layoutParams as? ViewGroup.MarginLayoutParams
        val sideMargins = (sheetParams?.marginStart ?: 0) + (sheetParams?.marginEnd ?: 0)
        val topMargin = sheetParams?.topMargin ?: 0
        // 高度这边还要扣掉两层纸边（fragment_note.xml 里各 9dp）和底部留白
        val byWidth = parent.width - sideMargins
        val byHeight = parent.height - topMargin - dp(9 + 9 + 16)
        val size = minOf(byWidth, byHeight)
        if (size <= 0 || binding.noteCard.layoutParams.height == size) return
        binding.noteCard.layoutParams = binding.noteCard.layoutParams.apply { height = size }
    }

    /**
     * 便签纸里的输入框要同时干三件事：点进去写字、上下滑动翻页、长按拖走删除。
     *
     * 分两条路走：
     * - 还没聚焦：整串事件由我们接管 —— 这样「滑动翻页」不会顺手把键盘弹出来；
     *   抬手时若没滑动过才算点击，这时才手动聚焦并把光标落到按下的位置。
     * - 已经在编辑：不插手，输入框自己处理光标/选择/滚动，只在它滚到头时放行给翻页；
     *   长按仍走输入框自己的长按回调（会被我们消费掉，不会弹出复制粘贴菜单）。
     */
    private fun setupGestures() {
        val swipe = binding.noteSwipeLayout
        swipe.onFlip = { step -> switchNote(step) }
        swipe.onDelete = { deleteCurrent() }
        swipe.onDragStateChange = { dragging ->
            binding.noteHint.setText(
                if (dragging) R.string.note_hint_drag else R.string.note_visibility_hint
            )
        }

        val input = binding.noteInput
        input.setOnLongClickListener {
            if (!swipe.isDragging) swipe.startDrag(downRawX, downRawY)
            true
        }
        input.setOnTouchListener { v, event ->
            val dragging = swipe.isDragging
            if (dragging) {
                // 拖拽已经起来了：剩下的移动全转给手势层
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> swipe.updateDrag(event.rawX, event.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipe.endDrag()
                }
                return@setOnTouchListener true
            }
            if (input.hasFocus()) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val dy = event.rawY - downRawY
                    // 输入框还能往这个方向滚就先让它滚，滚到头才允许翻页
                    v.parent?.requestDisallowInterceptTouchEvent(
                        v.canScrollVertically(if (dy > 0) -1 else 1)
                    )
                }
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    scheduleLongPress()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawY - downRawY) > touchSlop()) {
                        cancelLongPress()
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    if (abs(event.rawY - downRawY) <= touchSlop()) focusInputAt(event)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    true
                }
                else -> true
            }
        }
    }

    private fun touchSlop() = ViewConfiguration.get(requireContext()).scaledTouchSlop

    private fun scheduleLongPress() {
        cancelLongPress()
        val action = Runnable {
            if (!binding.noteSwipeLayout.isDragging) {
                binding.noteSwipeLayout.startDrag(downRawX, downRawY)
            }
        }
        longPressAction = action
        handler.postDelayed(action, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        longPressAction?.let { handler.removeCallbacks(it) }
        longPressAction = null
    }

    /** 点击便签纸：聚焦输入框、把光标落回按下的位置、拉起键盘。 */
    private fun focusInputAt(event: MotionEvent) {
        val input = binding.noteInput
        input.requestFocus()
        input.layout?.let { textLayout ->
            val line = textLayout.getLineForVertical(
                (event.y - input.totalPaddingTop).toInt().coerceAtLeast(0)
            )
            val offset = textLayout.getOffsetForHorizontal(line, event.x - input.totalPaddingLeft)
            input.setSelection(offset.coerceIn(0, input.text?.length ?: 0))
        }
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
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
            if (note.starred) R.drawable.ic_note_star_filled else R.drawable.ic_note_star_outline
        )
        val tint = if (note.starred) {
            requireContext().themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
        } else {
            ContextCompat.getColor(requireContext(), R.color.text_tertiary)
        }
        starred?.mutate()?.setTint(tint)
        binding.noteStarButton.setImageDrawable(starred)
        updateMeta()
    }

    /** 标题下面那行：「时间 · 字数」。翻页、删除、标星、敲字之后都要跟着变。 */
    private fun updateMeta() {
        val note = notes.find { it.createdAt == currentId } ?: return
        binding.noteMeta.text = getString(
            R.string.note_meta, friendlyTime(note.createdAt), note.text.trim().length
        )
    }

    /** 当天/昨天用口语说法，更早交给系统格式化（自动跟语言走，不用为每种语言配一套图案）。 */
    private fun friendlyTime(millis: Long): String {
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
        return when (dayKey.format(Date(millis))) {
            dayKey.format(Date()) -> getString(R.string.note_time_today, clock)
            dayKey.format(Date(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)) ->
                getString(R.string.note_time_yesterday, clock)
            else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
        }
    }

    /** -1 看更早的一张，+1 看更新的一张。返回 false 表示已经到头、该把纸弹回来。 */
    private fun switchNote(step: Int): Boolean {
        commitCurrent()
        val list = NoteStore.ordered(notes)
        val index = list.indexOfFirst { it.createdAt == currentId }
        if (index < 0) return false
        val target = index + step
        if (target < 0) return false
        if (target >= list.size) {
            // 最后一页再往上滑 = 新开一张（当前已是空白就别再开了）
            if (notes.find { it.createdAt == currentId }?.text.isNullOrBlank()) return false
            currentId = newNote().also { notes += it }.createdAt
        } else {
            currentId = list[target].createdAt
        }
        renderNote()
        NoteStore.save(requireContext(), notes)
        return true
    }

    /** 拖进垃圾桶：进回收站，接着显示它后面（更新）的那张；删空了补一张新的。 */
    private fun deleteCurrent() {
        val note = notes.find { it.createdAt == currentId } ?: return
        val list = NoteStore.ordered(notes)
        val index = list.indexOfFirst { it.createdAt == note.createdAt }
        // 必须先打删除戳再存盘：下面的 save 是整份重写，而它只保留「已在回收站里」的那些，
        // 顺序反了这张便贴就直接消失了（既不在活跃列表、也不在回收站）。
        // 空白便贴是「新开一张」留下的占位，拖掉它本来就是不要了，没必要在回收站里留一条。
        val trashed = note.text.isNotBlank()
        if (trashed) NoteStore.moveToTrash(requireContext(), note.createdAt)
        notes.remove(note)
        if (notes.isEmpty()) notes += newNote()
        val neighbour = list.getOrNull(index + 1)?.takeIf { it.createdAt != note.createdAt }
            ?: list.getOrNull(index - 1)?.takeIf { it.createdAt != note.createdAt }
        currentId = neighbour?.createdAt ?: notes.maxByOrNull { it.createdAt }!!.createdAt
        NoteStore.save(requireContext(), notes)
        renderNote()
        if (trashed) toastMovedToRecycleBin()
    }

    /** 点标题看全部便贴。一叠翻页适合随手写，但张数一多就得有个总览能直接跳过去。 */
    private fun showNoteList() {
        commitCurrent()
        val list = NoteStore.ordered(notes)
        if (list.isEmpty()) return
        val labels = list.map { note ->
            val head = note.text.replace('\n', ' ').trim().take(16)
            (if (note.starred) "★ " else "") + head.ifEmpty { getString(R.string.note_blank) }
        }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.note_list_title, list.size))
            .setItems(labels) { _, which ->
                currentId = list[which].createdAt
                renderNote()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleStar() {
        val note = notes.find { it.createdAt == currentId } ?: return
        note.starred = !note.starred
        renderNote()
        NoteStore.save(requireContext(), notes)
        // 点下去弹一下：实心/描边两态切换本身很静，手势上没有反馈会显得没点着
        binding.noteStarButton.animate().cancel()
        binding.noteStarButton.scaleX = 1f
        binding.noteStarButton.scaleY = 1f
        binding.noteStarButton.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110)
            .withEndAction {
                binding.noteStarButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            .start()
    }

    private fun commitCurrent() {
        notes.find { it.createdAt == currentId }?.text = binding.noteInput.text?.toString().orEmpty()
        NoteStore.save(requireContext(), notes)
    }
}
