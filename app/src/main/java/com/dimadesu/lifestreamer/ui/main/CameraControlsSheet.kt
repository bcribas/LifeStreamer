package com.dimadesu.lifestreamer.ui.main

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.camera.CameraControlManager
import com.dimadesu.lifestreamer.camera.ControlDescriptor
import com.dimadesu.lifestreamer.camera.ControlKeys
import com.dimadesu.lifestreamer.camera.ControlText
import com.dimadesu.lifestreamer.camera.ControlType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Every control of one camera, built from its descriptions: the same ones the remote page draws.
 * Opens on the selected layer's camera; the chips at the top pick another camera in use.
 *
 * The background is not dimmed, so the preview shows what each change does.
 */
class CameraControlsSheet : BottomSheetDialogFragment() {

    private val viewModel: PreviewViewModel by viewModels({ requireParentFragment() }) {
        PreviewViewModelFactory(requireActivity().application)
    }

    private lateinit var chips: LinearLayout
    private lateinit var note: TextView
    private lateinit var controls: LinearLayout

    /** The camera shown, by target id; null follows the selected layer. */
    private var targetId: String? = null
    private var chipsSignature: String? = null
    private var controlsSignature: String? = null

    /** Refreshes a built control with a new value, by key. */
    private val updaters = mutableMapOf<String, (ControlDescriptor) -> Unit>()

    /** Controls under the finger: incoming state must not move them. */
    private val held = mutableSetOf<String>()

    /**
     * The screen behind is on an AppCompat theme, where Material components cannot resolve their
     * attributes: the panel brings its own Material 3 theme, and its views use its context.
     */
    override fun getTheme(): Int = com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog

    private lateinit var themed: Context

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).roundToInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        themed = inflater.context
        val context = themed
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        column.addView(TextView(context).apply {
            text = getString(R.string.camera_controls_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        })
        chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        note = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(4))
        }
        column.addView(note)
        controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(controls)
        return ScrollView(context).apply { addView(column) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        targetId = viewModel.selectedCameraTargetLive.value?.id
        viewModel.cameraControlsState.observe(viewLifecycleOwner) { render(it) }
        viewModel.selectedCameraTargetLive.observe(viewLifecycleOwner) { selected ->
            // A layer picked on the bar (or by a tap) moves the panel to its camera
            if (selected != null && selected.id != targetId) {
                targetId = selected.id
                viewModel.cameraControlsState.value?.let { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setDimAmount(0f)
        val sheet = dialog as? BottomSheetDialog ?: return
        sheet.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // Landscape: a panel on the right, leaving the preview in view
        val metrics = resources.displayMetrics
        if (metrics.widthPixels > metrics.heightPixels) {
            sheet.behavior.maxWidth = (metrics.widthPixels * 0.45f).roundToInt()
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { panel ->
                (panel.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
                    it.gravity = Gravity.END or Gravity.BOTTOM
                    panel.layoutParams = it
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Not left over the app when it comes back, possibly on another camera
        dismissAllowingStateLoss()
    }

    private fun render(state: CameraControlManager.State) {
        val target = state.target(targetId) ?: state.targets.firstOrNull()
        renderChips(state, target?.id)
        if (target == null) {
            note.text = getString(R.string.camera_controls_none)
            controls.removeAllViews()
            updaters.clear()
            controlsSignature = null
            return
        }
        targetId = target.id
        note.text = if (target.active) {
            getString(R.string.camera_controls_remembered)
        } else {
            getString(R.string.camera_controls_off_note)
        }
        val signature = target.id + "|" + target.cameraKey + "|" + target.controls.joinToString(";") {
            "${it.key},${it.type},${it.min},${it.max},${it.options?.size},${it.enabled}"
        }
        if (signature != controlsSignature) {
            controlsSignature = signature
            build(target)
        } else {
            target.controls.forEach { control ->
                if (control.key !in held) updaters[control.key]?.invoke(control)
            }
        }
    }

    private fun renderChips(state: CameraControlManager.State, shownId: String?) {
        val signature = state.targets.joinToString("|") { "${it.id}:${it.label}" } + "@" + shownId
        if (signature == chipsSignature) return
        chipsSignature = signature
        chips.removeAllViews()
        if (state.targets.size < 2) return
        state.targets.forEach { target ->
            chips.addView(MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = target.label
                isAllCaps = false
                isCheckable = true
                isChecked = target.id == shownId
                setOnClickListener {
                    targetId = target.id
                    // The quick buttons and the bar follow the camera picked here
                    if (viewModel.isCompositeSource.value == true) viewModel.selectCompositionLayer(target.id)
                    viewModel.cameraControlsState.value?.let { render(it) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun build(target: CameraControlManager.CameraTargetState) {
        controls.removeAllViews()
        updaters.clear()
        held.clear()
        var group: String? = null
        target.controls.forEach { control ->
            if (control.group != group) {
                group = control.group
                ControlText.group(control.group).takeIf { it.isNotEmpty() }?.let { title ->
                    controls.addView(TextView(themed).apply {
                        text = title
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(14), 0, dp(2))
                    })
                }
            }
            val view = when (control.type) {
                ControlType.CHOICE -> choice(target, control)
                ControlType.TOGGLE -> toggle(target, control)
                ControlType.RANGE -> range(target, control)
                ControlType.ACTION -> action(target, control)
            }
            setEnabledDeep(view, control.enabled)
            controls.addView(view)
            if (!control.enabled && control.reason != null) {
                controls.addView(reasonText(control.reason))
            }
        }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) (0 until view.childCount).forEach { setEnabledDeep(view.getChildAt(it), enabled) }
    }

    private fun reasonText(reason: String) = TextView(themed).apply {
        text = reason
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        alpha = 0.6f
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setPadding(0, dp(6), 0, 0)
    }

    /**
     * Up to three options side by side (more do not fit), a handful behind a menu, and a long
     * ordered list (ISO, shutter) as a slider over its steps.
     */
    private fun choice(target: CameraControlManager.CameraTargetState, control: ControlDescriptor): View {
        val context = themed
        val options = control.options.orEmpty()
        if (options.size > MAX_MENU_OPTIONS) return steps(target, control)
        if (options.size <= 3) {
            val group = MaterialButtonToggleGroup(context).apply { isSingleSelection = true }
            val ids = options.associate { option ->
                val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = option.label
                    isAllCaps = false
                    setOnClickListener { viewModel.setCameraControl(target.id, control.key, option.value) }
                }
                group.addView(button)
                option.value to button.id
            }
            fun show(c: ControlDescriptor) {
                val id = ids[c.value as? String]
                if (id != null) group.check(id) else group.clearChecked()
            }
            show(control)
            updaters[control.key] = ::show
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context, control.label))
                addView(group)
            }
        }
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        fun show(c: ControlDescriptor) {
            button.text = "${c.label}: ${ControlText.choice(c) ?: "Auto"}"
        }
        show(control)
        updaters[control.key] = ::show
        button.setOnClickListener {
            PopupMenu(context, button).apply {
                options.forEachIndexed { index, option -> menu.add(0, index, index, option.label) }
                setOnMenuItemClickListener { item ->
                    viewModel.setCameraControl(target.id, control.key, options[item.itemId].value)
                    true
                }
            }.show()
        }
        return button
    }

    /** A long list of ordered choices as a slider over them; "Auto" until one is picked. */
    private fun steps(target: CameraControlManager.CameraTargetState, control: ControlDescriptor): View {
        val options = control.options.orEmpty()
        val title = label(themed, control.label)
        val slider = Slider(themed).apply {
            valueFrom = 0f
            valueTo = (options.size - 1).toFloat()
            stepSize = 1f
            setLabelFormatter { options.getOrNull(it.roundToInt())?.label.orEmpty() }
        }
        fun place(c: ControlDescriptor) {
            val index = options.indexOfFirst { it.value == c.value }
            if (index >= 0) slider.value = index.toFloat()
            title.text = "${c.label}: ${ControlText.choice(c) ?: "Auto"}"
        }
        place(control)
        updaters[control.key] = ::place
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val option = options.getOrNull(value.roundToInt()) ?: return@addOnChangeListener
            title.text = "${control.label}: ${option.label}"
            viewModel.setCameraControl(target.id, control.key, option.value)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                held += control.key
            }

            override fun onStopTrackingTouch(slider: Slider) {
                held -= control.key
            }
        })
        return LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(slider)
        }
    }

    private fun toggle(target: CameraControlManager.CameraTargetState, control: ControlDescriptor): View {
        val switch = MaterialSwitch(themed).apply {
            text = control.label
            isChecked = control.value == true
            // A click, not a change listener: showing a new value must not send it back
            setOnClickListener { viewModel.setCameraControl(target.id, control.key, isChecked) }
        }
        updaters[control.key] = { c -> switch.isChecked = c.value == true }
        if (control.key == ControlKeys.TORCH && control.reason != null) {
            return LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                addView(switch)
                addView(reasonText(control.reason))
            }
        }
        return switch
    }

    private fun range(target: CameraControlManager.CameraTargetState, control: ControlDescriptor): View {
        val context = themed
        val min = control.min ?: 0f
        val max = (control.max ?: 1f).coerceAtLeast(min + 0.01f)
        val stepped = control.step == 1f
        val title = label(context, control.label)
        val slider = Slider(context).apply {
            // Range first, then a value inside it and on a step: a Slider throws otherwise
            valueFrom = min
            valueTo = max
            stepSize = if (stepped) 1f else 0f
            setLabelFormatter { ControlText.value(control, it) }
        }
        fun place(c: ControlDescriptor) {
            val raw = (c.value as? Number)?.toFloat() ?: min
            val value = raw.coerceIn(min, max).let { if (stepped) it.roundToInt().toFloat() else it }
            slider.value = value
            title.text = "${c.label}: ${ControlText.value(c, value)}"
        }
        place(control)
        updaters[control.key] = ::place
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            title.text = "${control.label}: ${ControlText.value(control, value)}"
            viewModel.setCameraControl(target.id, control.key, if (stepped) value.roundToInt() else value)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                held += control.key
            }

            override fun onStopTrackingTouch(slider: Slider) {
                held -= control.key
            }
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(slider)
        }
    }

    private fun action(target: CameraControlManager.CameraTargetState, control: ControlDescriptor): View {
        val context = themed
        return MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = control.label
            isAllCaps = false
            setOnClickListener {
                if (control.key == ControlKeys.RESET) {
                    AlertDialog.Builder(context)
                        .setMessage(getString(R.string.camera_controls_reset_confirm, target.label))
                        .setPositiveButton(control.label) { _, _ ->
                            viewModel.setCameraControl(target.id, control.key, null)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    viewModel.setCameraControl(target.id, control.key, true)
                }
            }
        }
    }

    companion object {
        const val TAG = "CameraControlsSheet"

        /** White balance's eight fit a menu; ISO's third stops do not. */
        private const val MAX_MENU_OPTIONS = 8
    }
}
