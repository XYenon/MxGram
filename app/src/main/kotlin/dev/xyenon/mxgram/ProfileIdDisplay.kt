package dev.xyenon.mxgram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.reflect.Field
import java.util.WeakHashMap

internal class ProfileIdDisplay(
    private val logError: (String, Throwable) -> Unit,
) {
    private val views = WeakHashMap<Any, TextView>()
    private val fields = WeakHashMap<Class<*>, ProfileFields>()
    private var lastPresentationLog: String? = null

    fun install(profileActivity: Any) {
        try {
            Log.i(TAG, "ProfileIdDisplay.install for $profileActivity")
            val idView = viewFor(profileActivity)
            if (idView == null) {
                Log.w(TAG, "ProfileIdDisplay.install: viewFor returned null")
                return
            }
            updateText(profileActivity, idView)
            syncPresentation(profileActivity, idView)
        } catch (t: Throwable) {
            logError("Failed to install profile ID display", t)
        }
    }

    fun update(profileActivity: Any) {
        try {
            Log.i(TAG, "ProfileIdDisplay.update for $profileActivity")
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
        val profileFields = fieldsFor(profileActivity)
        val onlineView =
            (profileFields.onlineTextView.get(profileActivity) as? Array<*>)
                ?.getOrNull(1) as? View
        val nameView =
            (profileFields.nameTextView?.get(profileActivity) as? Array<*>)
                ?.getOrNull(1) as? View

        val container =
            (onlineView?.parent as? ViewGroup)
                ?: (nameView?.parent as? ViewGroup)

        if (container == null) {
            Log.w(TAG, "viewFor: container is null")
            return null
        }
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
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
                val paddingHorizontal = dp(4f)
                setPadding(paddingHorizontal, 0, paddingHorizontal, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = true
                isLongClickable = true
                setOnClickListener { copyId(this) }
                setOnLongClickListener { copyId(this) }
            }

        val lp =
            FrameLayout
                .LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT or Gravity.TOP,
                ).apply {
                    leftMargin = 0
                    topMargin = 0
                }
        container.addView(idView, lp)
        idView.elevation = dpFloat(20f)
        idView.translationZ = dpFloat(20f)
        container.clipChildren = false
        container.clipToPadding = false
        views[profileActivity] = idView
        Log.i(TAG, "viewFor: added idView to ${container.javaClass.simpleName} (childCount=${container.childCount})")

        val preDrawListener =
            ViewTreeObserver.OnPreDrawListener {
                syncPresentation(profileActivity, idView)
                true
            }
        container.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        container.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                }
            },
        )

        return idView
    }

    private fun updateText(
        profileActivity: Any,
        idView: TextView,
    ) {
        val profileFields = fieldsFor(profileActivity)
        var id = 0L

        // 1. Try fields userId / dialogId / chatId
        try {
            val userId = profileFields.userId?.getLong(profileActivity) ?: 0L
            val dialogId = profileFields.dialogId?.getLong(profileActivity) ?: 0L
            val chatId = profileFields.chatId?.getLong(profileActivity) ?: 0L

            if (userId != 0L) {
                id = userId
            } else if (dialogId != 0L) {
                id = dialogId
            } else if (chatId != 0L) {
                id = resolveChatApiId(profileActivity, chatId)
            }
            Log.i(TAG, "updateText (fields): userId=$userId, dialogId=$dialogId, chatId=$chatId => resolved id=$id")
        } catch (t: Throwable) {
            Log.d(TAG, "updateText: fields not available: ${t.message}")
        }

        // 2. Try getDialogId()
        if (id == 0L) {
            try {
                val dialogId =
                    findMethodOrNull(profileActivity.javaClass, "getDialogId")?.invoke(profileActivity) as? Long ?: 0L
                if (dialogId != 0L) {
                    id = dialogId
                    Log.i(TAG, "updateText (method): dialogId=$dialogId")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "updateText: getDialogId not available: ${t.message}")
            }
        }

        // 3. Try BaseFragment arguments Bundle
        if (id == 0L) {
            try {
                val args =
                    (
                        findMethodOrNull(profileActivity.javaClass, "getArguments")?.invoke(profileActivity)
                            ?: findFieldOrNull(profileActivity.javaClass, "arguments")?.get(profileActivity)
                    ) as? android.os.Bundle
                if (args != null) {
                    val userId = args.getLong("user_id", 0L)
                    val dialogId = args.getLong("dialog_id", 0L)
                    val chatId = args.getLong("chat_id", 0L)
                    if (userId != 0L) {
                        id = userId
                    } else if (dialogId != 0L) {
                        id = dialogId
                    } else if (chatId != 0L) {
                        id = resolveChatApiId(profileActivity, chatId)
                    }
                    Log.i(TAG, "updateText (args): userId=$userId, dialogId=$dialogId, chatId=$chatId => resolved id=$id")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "updateText: arguments not available: ${t.message}")
            }
        }

        if (id == 0L) {
            Log.w(TAG, "updateText: no ID resolved, hiding idView")
            idView.text = ""
            idView.tag = null
            idView.visibility = View.GONE
            return
        }

        idView.tag = id
        val dc = resolveDcId(profileActivity, id)
        val text = if (dc > 0) "ID: $id, DC: $dc" else "ID: $id"
        Log.i(TAG, "updateText: set text='$text' (id=$id, dc=$dc)")
        if (idView.text?.toString() != text) {
            idView.text = text
            ensureMeasured(idView)
        }
    }

    private fun resolveChatApiId(
        profileActivity: Any,
        chatId: Long,
    ): Long {
        try {
            val messagesController =
                findMethodOrNull(profileActivity.javaClass, "getMessagesController")?.invoke(profileActivity)
            if (messagesController != null) {
                val chat =
                    (
                        findMethodOrNull(messagesController.javaClass, "getChat", java.lang.Long::class.java)
                            ?: findMethodOrNull(messagesController.javaClass, "getChat", java.lang.Long.TYPE)
                    )?.invoke(messagesController, chatId)
                if (chat != null) {
                    val isChannel =
                        chat.javaClass.name.contains("Channel", ignoreCase = true) ||
                            try {
                                val chatObjectClass =
                                    Class.forName("org.telegram.messenger.ChatObject", false, chat.javaClass.classLoader)
                                invokeStaticBoolean(chatObjectClass, "isChannel", arrayOf(chat))
                            } catch (_: Throwable) {
                                false
                            }
                    if (isChannel) {
                        return -1000000000000L - chatId
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return -chatId
    }

    private fun resolveDcId(
        profileActivity: Any,
        id: Long,
    ): Int {
        try {
            if (id > 0) {
                val messagesController =
                    findMethodOrNull(profileActivity.javaClass, "getMessagesController")?.invoke(profileActivity)
                if (messagesController != null) {
                    val user =
                        (
                            findMethodOrNull(messagesController.javaClass, "getUser", java.lang.Long::class.java)
                                ?: findMethodOrNull(messagesController.javaClass, "getUser", java.lang.Long.TYPE)
                        )?.invoke(messagesController, id)
                    if (user != null) {
                        val photo = findFieldOrNull(user.javaClass, "photo")?.get(user)
                        if (photo != null) {
                            val dcId = findFieldOrNull(photo.javaClass, "dc_id")?.getInt(photo) ?: 0
                            if (dcId != 0) return dcId
                        }
                    }
                }
            } else {
                val profileFields = fieldsFor(profileActivity)
                val chatInfo = profileFields.chatInfo?.get(profileActivity)
                if (chatInfo != null) {
                    val dcId = findFieldOrNull(chatInfo.javaClass, "stats_dc")?.getInt(chatInfo) ?: 0
                    if (dcId != 0) return dcId
                }
            }
        } catch (_: Throwable) {
        }
        return 0
    }

    private fun syncPresentation(
        profileActivity: Any,
        idView: TextView,
    ) {
        if (idView.text.isNullOrEmpty()) {
            idView.visibility = View.GONE
            return
        }

        val profileFields = fieldsFor(profileActivity)
        val onlineView =
            (profileFields.onlineTextView.get(profileActivity) as? Array<*>)
                ?.getOrNull(1) as? View
        val nameView =
            (profileFields.nameTextView?.get(profileActivity) as? Array<*>)
                ?.getOrNull(1) as? View

        val anchorView: View? =
            when {
                onlineView != null && onlineView.visibility == View.VISIBLE -> onlineView
                nameView != null && nameView.visibility == View.VISIBLE -> nameView
                onlineView != null -> onlineView
                nameView != null -> nameView
                else -> null
            }

        if (anchorView == null) {
            idView.visibility = View.GONE
            return
        }

        ensureMeasured(idView)
        ensureMeasured(anchorView)

        val idWidth = if (idView.measuredWidth > 0) idView.measuredWidth else idView.width
        val idHeight = if (idView.measuredHeight > 0) idView.measuredHeight else idView.height

        if (idWidth > 0 && idHeight > 0) {
            idView.layout(0, 0, idWidth, idHeight)
        }

        val container = idView.parent as? ViewGroup
        if (container != null) {
            container.clipChildren = false
            container.clipToPadding = false
            (container.parent as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
        }

        val containerWidth = container?.width?.takeIf { it > 0 } ?: (container?.parent as? View)?.width ?: 0
        val textWidth =
            (findMethodOrNull(anchorView.javaClass, "getExactWidth")?.invoke(anchorView) as? Float)
                ?: ((findMethodOrNull(anchorView.javaClass, "getTextWidth")?.invoke(anchorView) as? Number)?.toFloat())
                ?: 0f

        val anchorWidth = if (textWidth > 0f) textWidth else (if (anchorView.width > 0) anchorView.width.toFloat() else dpFloat(60f))
        val screenWidth = if (containerWidth > 0) containerWidth.toFloat() else (density * 409f)
        val screenCenterX = screenWidth / 2f
        val expandedX = screenCenterX - anchorWidth / 2f
        val collapsedX = dpFloat(64f)
        val span = expandedX - collapsedX

        // Smoothly interpolate centering factor from 1.0 (at default state) down to 0.0 (when scrolled/collapsed)
        // using Hermite smoothstep to eliminate any sudden jumping during vertical scrolling.
        val rawProgress = if (span > 1f) ((anchorView.x - collapsedX) / span).coerceIn(0f, 1f) else 0f
        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
        val targetX = anchorView.x + smoothProgress * ((anchorWidth - idWidth) / 2f)

        val minX = dpFloat(16f)
        val maxX = if (containerWidth > 0) (containerWidth - idWidth - dpFloat(16f)) else Float.MAX_VALUE
        val clampedX = targetX.coerceIn(minX, maxOf(minX, maxX))
        val verticalOffset = if (anchorView === nameView) dpFloat(26f) else dpFloat(16.5f)
        val targetY = anchorView.y + verticalOffset

        // Fade based on header collapse: when the anchor view approaches the action bar,
        // the header is collapsing and the ID should fade out. This replaces the original
        // extraHeight-based fade with a position-based proxy that doesn't require resolving
        // the obfuscated extraHeight field.
        val actionBarHeight =
            try {
                val actionBar = profileActivity.javaClass.getMethod("getActionBar").invoke(profileActivity)
                val abHeight = actionBar?.javaClass?.getMethod("getHeight")?.invoke(actionBar) as? Int ?: dp(56f)
                val statusBar = actionBar?.javaClass?.getMethod("getOccupyStatusBar")?.invoke(actionBar) as? Boolean ?: false
                val statusBarHeight =
                    if (statusBar) {
                        val resId =
                            android.content.res.Resources
                                .getSystem()
                                .getIdentifier("status_bar_height", "dimen", "android")
                        if (resId > 0) {
                            android.content.res.Resources
                                .getSystem()
                                .getDimensionPixelSize(resId)
                        } else {
                            dp(24f)
                        }
                    } else {
                        0
                    }
                abHeight + statusBarHeight
            } catch (_: Throwable) {
                dp(56f) + dp(24f)
            }

        val fadeByHeight =
            run {
                // anchorView.y is relative to its container (avatarContainer2), which starts at the top.
                // When the idView's targetY is close to the actionBar bottom, fade out.
                val idBottom = targetY + idHeight
                val margin = idBottom - actionBarHeight
                val fadeRange = dpFloat(40f)
                val raw = (margin / fadeRange).coerceIn(0f, 1f)
                raw * raw * (3f - 2f * raw)
            }

        // Fade based on distance to content card below (hides when scrolled up and overlapping content)
        val parentView = container?.parent as? ViewGroup
        val listView: ViewGroup? =
            parentView?.let { parent ->
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is ViewGroup && (child.javaClass.name.contains("Recycler") || child.javaClass.name.contains("ListView"))) {
                        return@let child
                    }
                }
                null
            }

        val firstContentCard: View? =
            listView?.let { lv ->
                for (i in 0 until lv.childCount) {
                    val child = lv.getChildAt(i)
                    if (child != null && child.height > dp(20f)) {
                        return@let child
                    }
                }
                null
            }

        val distance: Float? =
            if (firstContentCard != null) {
                try {
                    val containerLoc = IntArray(2).also { container.getLocationInWindow(it) }
                    val cardLoc = IntArray(2).also { firstContentCard.getLocationInWindow(it) }
                    val estimatedIdBottomInWindow = containerLoc[1] + targetY + idHeight
                    val contentTopInWindow = cardLoc[1].toFloat()
                    contentTopInWindow - estimatedIdBottomInWindow
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

        val fadeByDistance =
            if (distance != null) {
                val raw = (distance / dpFloat(40f)).coerceIn(0f, 1f)
                raw * raw * (3f - 2f * raw)
            } else {
                1.0f
            }

        val scrollFade = minOf(fadeByHeight, fadeByDistance)
        // Use anchor view's own alpha (Telegram already animates it during expand/collapse)
        val finalAlpha = anchorView.alpha * scrollFade

        idView.x = clampedX
        idView.y = targetY
        idView.alpha = finalAlpha

        val color = resolveSubtitleColor(profileActivity, onlineView ?: anchorView)
        idView.setTextColor(color)

        val isVisible = finalAlpha > 0.02f && anchorView.visibility == View.VISIBLE
        idView.visibility = if (isVisible) View.VISIBLE else View.GONE

        val logKey =
            "text='${idView.text}' vis=${idView.visibility} alpha=${idView.alpha} " +
                "x=${idView.x} y=${idView.y} w=${idView.width} h=${idView.height} color=${Integer.toHexString(color)} | " +
                "anchor=${anchorView.javaClass.simpleName} ax=${anchorView.x} ay=${anchorView.y} progress=$smoothProgress " +
                "fade=$scrollFade fadeH=$fadeByHeight fadeD=$fadeByDistance dist=$distance " +
                "avis=${anchorView.visibility} aalpha=${anchorView.alpha}"
        if (logKey != lastPresentationLog) {
            lastPresentationLog = logKey
            Log.i(TAG, "syncPresentation: $logKey")
        }
    }

    private fun resolveSubtitleColor(
        profileActivity: Any,
        onlineView: View?,
    ): Int {
        if (onlineView != null) {
            try {
                val color = onlineView.javaClass.getMethod("getTextColor").invoke(onlineView) as Int
                if (color != 0) {
                    return color
                }
            } catch (_: Throwable) {
            }
        }
        return try {
            val classLoader = profileActivity.javaClass.classLoader
            val themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, classLoader)
            val key = getStaticIntFieldValue(themeClass, "key_actionBarDefaultSubtitle", -1)
            val color =
                profileActivity.javaClass
                    .getMethod("getThemedColor", java.lang.Integer.TYPE)
                    .invoke(profileActivity, key) as Int
            if (color != 0) color else 0xB3FFFFFF.toInt()
        } catch (_: Throwable) {
            0xB3FFFFFF.toInt()
        }
    }

    private fun copyId(idView: TextView): Boolean {
        val id = idView.tag as? Long ?: return false
        return try {
            val clipboard =
                idView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("Telegram ID", id.toString()))
            try {
                idView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Throwable) {
            }
            Toast.makeText(idView.context, "ID copied: $id", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "copyId: copied $id to clipboard")
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

    private fun fieldsFor(profileActivity: Any): ProfileFields =
        fields.getOrPut(profileActivity.javaClass) {
            val type = profileActivity.javaClass
            ProfileFields(
                onlineTextView = TelegramObfuscationResolver.findProfileOnlineTextViewField(profileActivity),
                nameTextView = TelegramObfuscationResolver.findProfileNameTextViewFieldOrNull(profileActivity),
                userId = TelegramObfuscationResolver.findProfileUserIdFieldOrNull(type),
                chatId = TelegramObfuscationResolver.findProfileChatIdFieldOrNull(type),
                dialogId = TelegramObfuscationResolver.findProfileDialogIdFieldOrNull(type),
                chatInfo = TelegramObfuscationResolver.findProfileChatInfoFieldOrNull(type),
                userInfo = TelegramObfuscationResolver.findProfileUserInfoFieldOrNull(type),
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
        val onlineTextView: Field,
        val nameTextView: Field?,
        val userId: Field?,
        val chatId: Field?,
        val dialogId: Field?,
        val chatInfo: Field?,
        val userInfo: Field?,
    )

    companion object {
        private const val TAG = "MxGram"
    }
}
