/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.yuyan.inputmethod.core
import androidx.annotation.Keep
import com.yuyan.imemodule.application.CustomConstant
import kotlin.system.measureTimeMillis

/**
 * Rime JNI and instance methods
 */

class Rime(fullCheck: Boolean) {

    init {
        startup(fullCheck)
    }

    companion object {
        private var instance: Rime? = null

        @JvmStatic
        fun getInstance(fullCheck: Boolean = false): Rime {
            if (instance == null) instance = Rime(fullCheck)
            return instance!!
        }

        private var mContext: RimeContext? = null
        private var mStatus: RimeStatus? = null
        private var isHandlingRimeNotification = false


        init {
            System.loadLibrary("rime_jni")
        }

        private fun startup(fullCheck: Boolean) {
            isHandlingRimeNotification = false
            val sharedDataDir = CustomConstant.RIME_DICT_PATH
            val userDataDir = CustomConstant.RIME_DICT_PATH
            startupRime(sharedDataDir, userDataDir, fullCheck)
            updateStatus()
        }
        @JvmStatic
        fun destroy() {
            exitRime()
            instance = null
        }

        fun deploy() {
            destroy()
            getInstance(false)
        }

        fun updateStatus() {
            measureTimeMillis {
                mStatus = getRimeStatus() ?: RimeStatus()
            }
        }

        fun updateContext() {
            measureTimeMillis {
                mContext = getRimeContext() ?: RimeContext()
            }
            updateStatus()
        }

        @JvmStatic
        val isComposing get() = mStatus?.isComposing ?: false

        @JvmStatic
        val isAsciiMode get() = mStatus?.isAsciiMode ?: true

        @JvmStatic
        val isAsciiPunch get() = mStatus?.isAsciiPunch ?: true

        @JvmStatic
        val currentSchemaName get() = mStatus?.schemaName ?: ""

        @JvmStatic
        fun hasMenu(): Boolean {
            return isComposing && mContext?.menu?.numCandidates != 0
        }

        @JvmStatic
        fun hasLeft(): Boolean {
            return hasMenu() && mContext?.menu?.pageNo != 0
        }

        @JvmStatic
        fun hasRight(): Boolean {
            return hasMenu() && mContext?.menu?.isLastPage == false
        }

        @JvmStatic
        fun showAsciiPunch(): Boolean {
            return mStatus?.isAsciiPunch == true || mStatus?.isAsciiMode == true
        }

        @JvmStatic
        val composition: RimeComposition?
            get() = mContext?.composition

        @JvmStatic
        val compositionText: String
            get() = composition?.preedit ?: ""

        @JvmStatic
        val composingText: String
            get() = mContext?.commitTextPreview ?: ""

        @JvmStatic
        val selectLabels: Array<String>
            get() = mContext?.selectLabels ?: arrayOf()

        @JvmStatic
        fun isVoidKeycode(keycode: Int): Boolean {
            val voidSymbol = 0xffffff
            return keycode <= 0 || keycode == voidSymbol
        }

        // KeyProcess 调用JNI方法发送keycode和mask
        @JvmStatic
        fun processKey(keycode: Int, mask: Int): Boolean {
            if (isVoidKeycode(keycode)) return false
            return processRimeKey(keycode, mask).also {
                updateContext()
            }
        }

        @JvmStatic
        fun replaceKey(caretPos: Int, length: Int, key: String): Boolean {
            return replaceRimeKey(caretPos, length, key).also {
                updateContext()
            }
        }

        private fun isValidText(text: CharSequence?): Boolean {
            if (text.isNullOrEmpty()) return false
            val ch = text.toString().codePointAt(0)
            return ch in 0x20..0x7f
        }

        @JvmStatic
        fun simulateKeySequence(sequence: CharSequence): Boolean {
            if (!isValidText(sequence)) return false
            return simulateRimeKeySequence(
                sequence.toString().replace("{}", "{braceleft}{braceright}"),
            ).also {
                updateContext()
            }
        }

        @JvmStatic
        val candidatesOrStatusSwitches: Array<CandidateListItem>
            get() {
//                val showSwitches = AppPrefs.defaultInstance().keyboard.switchesEnabled
//                return if (!isComposing && showSwitches) {
//                    SchemaManager.getStatusSwitches()
//                } else {
                return mContext?.candidates ?: arrayOf()
//                }
            }
        @JvmStatic
        val candidatesWithoutSwitch: Array<CandidateListItem>
            get() = if (isComposing) mContext?.candidates ?: arrayOf() else arrayOf()

        @JvmStatic
        val candHighlightIndex: Int
            get() = if (isComposing) mContext?.menu?.highlightedCandidateIndex ?: -1 else -1

        fun commitComposition(): Boolean {
            return commitRimeComposition().also {
                updateContext()
            }
        }
        @JvmStatic
        fun clearComposition() {
            clearRimeComposition()
            updateContext()
        }
        @JvmStatic
        fun selectCandidate(index: Int): Boolean {
            return selectRimeCandidate(index).also {
                updateContext()
            }
        }

        fun selectRimeCandidateCurrentPage(index: Int): Boolean {
            return selectRimeCandidateOnCurrentPage(index).also {
                updateContext()
            }
        }

        fun deleteCandidate(index: Int): Boolean {
            return deleteRimeCandidateOnCurrentPage(index).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setOption(
            option: String,
            value: Boolean,
        ) {
            if (isHandlingRimeNotification) return
            setRimeOption(option, value)
        }

        @JvmStatic
        fun getOption(option: String): Boolean {
            return getRimeOption(option)
        }

        fun toggleOption(option: String) {
            setOption(option, !getOption(option))
        }

        @JvmStatic
        val isEmpty: Boolean
            get() = getCurrentRimeSchema() == ".default" // 無方案

        @JvmStatic
        fun selectSchema(schemaId: String): Boolean {
            return selectRimeSchema(schemaId).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setCaretPos(caretPos: Int) {
            setRimeCaretPos(caretPos)
            updateContext()
        }

        fun getAssociateList(key: String?): Array<String?> {
            return getRimeAssociateList(key)
        }

        fun chooseAssociate(index: Int): Boolean {
            return chooseRimeAssociate(index)
        }


        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun replaceRimeKey(
            caretPos: Int,
            length: Int,
            key: String?
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): RimeCommit?

        @JvmStatic
        external fun getRimeContext(): RimeContext?

        @JvmStatic
        external fun getRimeStatus(): RimeStatus?

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun getRimeConfigMap(
            configId: String,
            key: String,
        ): Map<String, Any>?

        @JvmStatic
        external fun setRimeCustomConfigInt(
            configId: String,
            keyValuePairs: Array<Pair<String?, Int?>?>,
        )

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String?

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int): Boolean

        @JvmStatic
        external fun selectRimeCandidateOnCurrentPage(index: Int): Boolean

        @JvmStatic
        external fun deleteRimeCandidateOnCurrentPage(index: Int): Boolean

        @JvmStatic
        external fun getLibrimeVersion(): String

        // module
        @JvmStatic
        external fun runRimeTask(taskName: String?): Boolean

        @JvmStatic
        external fun getRimeSharedDataDir(): String?

        @JvmStatic
        external fun getRimeUserDataDir(): String?

        @JvmStatic
        external fun getRimeSyncDir(): String?

        @JvmStatic
        external fun getRimeUserId(): String?

        // key_table
        @JvmStatic
        external fun getRimeModifierByName(name: String): Int

        @JvmStatic
        external fun getRimeKeycodeByName(name: String): Int

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeAssociateList(key: String?): Array<String?>

        @JvmStatic
        external fun chooseRimeAssociate(index: Int): Boolean

        @JvmStatic
        external fun getRimeStateLabel(
            optionName: String,
            state: Boolean,
        ): String?

        /** call from rime_jni */
        @Keep
        @JvmStatic
        fun handleRimeNotification(
            messageType: String,
            messageValue: String,
        ) {

        }
    }
}
