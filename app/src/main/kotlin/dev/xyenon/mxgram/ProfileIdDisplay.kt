package dev.xyenon.mxgram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.reflect.Field
import java.util.WeakHashMap
import kotlin.math.min

internal class ProfileIdDisplay(
    private val logError: (String, Throwable) -> Unit,
) {
    private val views = WeakHashMap<Any, TextView>()
    private val fields = WeakHashMap<Class<*>, ProfileFields>()

    fun install(profileActivity: Any) {
        try {
            val idView = viewFor(profileActivity) ?: return
            updateText(profileActivity, idView)
            syncPresentation(profileActivity, idView)
        } catch (t: Throwable) {
            logError("Failed to install profile ID display", t)
        }
    }

    fun update(profileActivity: Any) {
        try {
            val idView = viewFor(profileActivity) ?: return
            updateText(profileActivity, idView)
            syncPresentation(profileActivity, idView)
        } catch (t: Throwable) {
            logError("Failed to update profile ID display", t)
        }
    }

    fun sync(profileActivity: Any) {
        try {
            val idView = views[profileActivity] ?: return
            syncPresentation(profileActivity, idView)
        } catch (t: Throwable) {
            logError("Failed to sync profile ID display", t)
        }
    }

    private fun viewFor(profileActivity: Any): TextView? {
        val profileFields = fieldsFor(profileActivity.javaClass)
        val container = profileFields.avatarContainer2.get(profileActivity) as? FrameLayout ?: return null
        val existing = views[profileActivity]
        if (existing != null && existing.parent === container) {
            return existing
        }
        if (existing?.parent is ViewGroup) {
            (existing.parent as ViewGroup).removeView(existing)
        }

        val idView =
            TextView(container.context).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                val paddingHorizontal = dp(4f)
                val paddingVertical = dp(2f)
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                setOnLongClickListener { copyId(this) }
            }
        container.addView(
            idView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP,
            ),
        )
        views[profileActivity] = idView
        return idView
    }

    private fun updateText(
        profileActivity: Any,
        idView: TextView,
    ) {
        val profileFields = fieldsFor(profileActivity.javaClass)
        val userId = profileFields.userId.getLong(profileActivity)
        val chatId = profileFields.chatId.getLong(profileActivity)
        val id =
            when {
                userId != 0L -> userId
                chatId != 0L -> chatId
                else -> 0L
            }
        if (id == 0L) {
            idView.text = ""
            idView.tag = null
            idView.visibility = View.GONE
            return
        }
        idView.tag = id
        val text = "ID: $id"
        if (idView.text?.toString() != text) {
            idView.text = text
            idView.requestLayout()
        }
    }

    private fun syncPresentation(
        profileActivity: Any,
        idView: TextView,
    ) {
        if (idView.text.isNullOrEmpty()) {
            idView.visibility = View.GONE
            return
        }

        val profileFields = fieldsFor(profileActivity.javaClass)
        val avatarContainer = profileFields.avatarContainer.get(profileActivity) as? View ?: return
        if (!avatarContainer.isShown) {
            idView.visibility = View.GONE
            return
        }

        val onlineView =
            (profileFields.onlineTextView.get(profileActivity) as? Array<*>)
                ?.getOrNull(1) as? View
        idView.setTextColor(resolveSubtitleColor(profileActivity, onlineView))

        val avatarWidth = avatarContainer.width * avatarContainer.scaleX
        val visibleFraction = ((avatarWidth - dpFloat(42f)) / dpFloat(58f)).coerceIn(0f, 1f)
        if (visibleFraction <= 0.01f) {
            idView.visibility = View.GONE
            return
        }

        idView.visibility = View.VISIBLE
        ensureMeasured(idView)

        val avatarCenterX = avatarContainer.x + avatarWidth / 2f
        idView.x = avatarCenterX - idView.measuredWidth / 2f
        idView.y = resolveIdY(avatarContainer, onlineView)
        idView.alpha = min(visibleFraction, onlineView?.alpha ?: 1f)
    }

    private fun resolveIdY(
        avatarContainer: View,
        onlineView: View?,
    ): Float {
        if (onlineView != null && onlineView.visibility == View.VISIBLE && onlineView.height > 0) {
            return onlineView.y + onlineView.height + dpFloat(2f)
        }
        return avatarContainer.y + avatarContainer.height * avatarContainer.scaleY + dpFloat(32f)
    }

    private fun resolveSubtitleColor(
        profileActivity: Any,
        onlineView: View?,
    ): Int {
        if (onlineView != null) {
            try {
                return onlineView.javaClass.getMethod("getTextColor").invoke(onlineView) as Int
            } catch (_: Throwable) {
                // Fall through to Telegram's themed subtitle color.
            }
        }
        return try {
            val classLoader = profileActivity.javaClass.classLoader
            val themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, classLoader)
            val key = getStaticIntFieldValue(themeClass, "key_actionBarDefaultSubtitle", -1)
            profileActivity.javaClass
                .getMethod("getThemedColor", java.lang.Integer.TYPE)
                .invoke(profileActivity, key) as Int
        } catch (_: Throwable) {
            0x99000000.toInt()
        }
    }

    private fun copyId(idView: TextView): Boolean {
        val id = idView.tag as? Long ?: return false
        return try {
            val clipboard =
                idView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("Telegram ID", id.toString()))
            Toast.makeText(idView.context, "ID copied", Toast.LENGTH_SHORT).show()
            true
        } catch (t: Throwable) {
            logError("Failed to copy profile ID", t)
            false
        }
    }

    private fun ensureMeasured(view: View) {
        if (view.measuredWidth > 0 && view.measuredHeight > 0) {
            return
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
    }

    private fun fieldsFor(type: Class<*>): ProfileFields =
        fields.getOrPut(type) {
            ProfileFields(
                avatarContainer2 = findField(type, "avatarContainer2"),
                avatarContainer = findField(type, "avatarContainer"),
                onlineTextView = findField(type, "onlineTextView"),
                userId = findField(type, "userId"),
                chatId = findField(type, "chatId"),
            )
        }

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private fun dpFloat(value: Float): Float = value * density

    private val density: Float
        get() =
            android.content.res.Resources
                .getSystem()
                .displayMetrics.density

    private data class ProfileFields(
        val avatarContainer2: Field,
        val avatarContainer: Field,
        val onlineTextView: Field,
        val userId: Field,
        val chatId: Field,
    )
}
