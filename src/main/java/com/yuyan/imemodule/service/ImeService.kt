package com.yuyan.imemodule.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.Observer
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.IKeyboardView
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private var isHardwareKeyboard = false
    private var showVirtualKeyboardOnPhysicalKeyboard = false
    private lateinit var  candidateView:CandidateView
    private lateinit var  inputView:InputView
    private lateinit var mInputView:IKeyboardView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (::mInputView.isInitialized) mInputView.updateTheme()}
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    if (::mInputView.isInitialized) mInputView.showSymbols(arrayOf(value))
                }
            }
        }
    }
    private val showVirtualKeyboardOnPhysicalKeyboardListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        showVirtualKeyboardOnPhysicalKeyboard = value
        handleHardwareKeyboard()
        updateInputViewShown()
    }
    private val physicalKeyboardDoublePinyinListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        syncPhysicalKeyboardSchema()
    }
    private val physicalKeyboardDoublePinyinSchemaListener = ManagedPreference.OnChangeListener<DoublePinyinSchemaMode> { _, _ ->
        syncPhysicalKeyboardSchema()
    }
    private val candidatesObserver = Observer<Any?> { _ ->
        if (::mInputView.isInitialized) mInputView.onCandidateChanged()
    }

    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.registerOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().keyboardSetting.physicalKeyboardDoublePinyin.registerOnChangeListener(physicalKeyboardDoublePinyinListener)
        getInstance().keyboardSetting.physicalKeyboardDoublePinyinSchema.registerOnChangeListener(physicalKeyboardDoublePinyinSchemaListener)
        handleHardwareKeyboard()
        DictDecoder.candidatesLiveData.observeForever(candidatesObserver)
    }

    override fun onCreateInputView(): View {
        if(!::inputView.isInitialized)inputView = InputView(baseContext, this)
        if(!isHardwareKeyboard)mInputView = inputView
        return inputView
    }

    override fun onCreateCandidatesView(): View {
        if(!::candidateView.isInitialized)candidateView = CandidateView(baseContext, this)
        if(isHardwareKeyboard) mInputView = candidateView
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        return candidateView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return if(showVirtualKeyboardOnPhysicalKeyboard) true else !isHardwareKeyboard
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        if(editorInfo != null) {
            YuyanEmojiCompat.setEditorInfo(editorInfo)
            InputModeSwitcher.requestInputWithSkb(editorInfo)
        }
        updateCandidatesViewShown(isHardwareKeyboard)
        syncPhysicalKeyboardSchema()
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (::mInputView.isInitialized) mInputView.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        DictDecoder.candidatesLiveData.removeObserver(candidatesObserver)
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.unregisterOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().keyboardSetting.physicalKeyboardDoublePinyin.unregisterOnChangeListener(physicalKeyboardDoublePinyinListener)
        getInstance().keyboardSetting.physicalKeyboardDoublePinyinSchema.unregisterOnChangeListener(physicalKeyboardDoublePinyinSchemaListener)
        removeOnChangedListener(onThemeChangeListener)
        getInstance().internal.clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasHardwareKeyboard = isHardwareKeyboard
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            handleHardwareKeyboard()
            EnvironmentSingleton.instance.initData(baseContext)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            if (::mInputView.isInitialized) mInputView.setConfiguration(newConfig)
            if (wasHardwareKeyboard && !isHardwareKeyboard) {
                // 硬件键盘断开：重新打开输入法，避免窗口停留在候选模式尺寸。
                if (::mInputView.isInitialized) mInputView.requestLayout()
                if (currentInputConnection != null) {
                    requestHideSelf(0)
                    delay(150)
                    requestShowSelf(0)
                }
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (::mInputView.isInitialized) mInputView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyUp(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyUp(keyCode, event)
        else if (::mInputView.isInitialized) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun setCandidatesView(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        super.setCandidatesView(view)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    override fun onComputeInsets(outInsets: Insets) {
        val (x, y, width, height) = if (::mInputView.isInitialized) mInputView.getKeyboardRect() else intArrayOf(0, 0, 0,0)
        outInsets.apply {
            if(EnvironmentSingleton.instance.keyboardModeFloat) {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + width, y + height)
            } else {
                contentTopInsets = y
                touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                touchableRegion.setEmpty()
                visibleTopInsets = y
            }
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
       super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        if (::mInputView.isInitialized) mInputView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        if (::mInputView.isInitialized) mInputView.onWindowShown()
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        if (::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = currentInputConnection ?: return
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        if (keyEventCode == KeyEvent.KEYCODE_DEL && !alt && !ctrl && !shift) {
            deleteBackward()
            return
        }
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendCursorKeyEvent(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false): Boolean {
        val inputConnection = currentInputConnection ?: return false
        if (keyEventCode == KeyEvent.KEYCODE_DPAD_LEFT || keyEventCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val surroundingText = if (keyEventCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                inputConnection.getTextBeforeCursor(1, 0)
            } else {
                inputConnection.getTextAfterCursor(1, 0)
            }
            if (surroundingText != null && surroundingText.isEmpty()) return false
        }
        sendCombinationKeyEvents(keyEventCode, alt, ctrl, shift)
        return true
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection?.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection?.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection?.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection?.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection?.getTextBeforeCursor(length, 0)?.toString().orEmpty()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection?.performEditorAction(editorAction)
    }

    fun deleteBackward() {
        val inputConnection = currentInputConnection ?: return
        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) {
            // deleteSurroundingText does not remove a selection; replace the selection with empty text.
            inputConnection.commitText("", 1)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Delete one code point so emoji (surrogate pairs) are removed in a single press.
            inputConnection.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            inputConnection.deleteSurroundingText(1, 0)
        }
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection?.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection?.setSelection(start, end)
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null): Boolean {
        val hardwareKeyboard = if (showVirtualKeyboardOnPhysicalKeyboard) false
            else if (newConfig != null) hasHardwareKeyboard(newConfig)
            else hasHardwareKeyboard(resources.configuration)
        isHardwareKeyboard = hardwareKeyboard
        updateCandidatesViewShown(hardwareKeyboard)
        syncPhysicalKeyboardSchema()
        return hardwareKeyboard
    }

    private fun syncPhysicalKeyboardSchema() {
        if (!InputModeSwitcher.isChinese) return
        val keyboardSetting = getInstance().keyboardSetting
        val schema = if (isHardwareKeyboard && keyboardSetting.physicalKeyboardDoublePinyin.getValue()) {
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + keyboardSetting.physicalKeyboardDoublePinyinSchema.getValue()
        } else {
            getInstance().internal.pinyinModeRime.getValue()
        }
        if (InputDispatcher.getCurrentRimeSchema() != schema) {
            InputDispatcher.initImeSchema(schema)
        }
    }

    private fun hasHardwareKeyboard(config: Configuration): Boolean {
        return config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    }

    fun updateCandidatesViewShown(shown: Boolean) {
        setCandidatesViewShown(shown)
    }

}
