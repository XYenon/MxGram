package dev.xyenon.mxgram

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

class BlockAnimateToNextChatHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = null
}

class CreateViewHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val chatActivity = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().disableDoubleTapReaction(chatActivity, chatActivity.javaClass.classLoader)
        return result
    }
}

class SelectReactionHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args
        if (args.size > 7 && java.lang.Boolean.TRUE == args[7]) {
            return null
        }
        return chain.proceed()
    }
}

class DisableGreetingStickerHooker : XposedInterface.Hooker {
    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? = chain.proceed(arrayOf<Any?>(null))
}

class PullingDownTargetHooker : XposedInterface.Hooker {
    @Throws(Throwable::class)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val pullingDownDrawable = chain.thisObject ?: return result
        try {
            TelegramHooksModule.currentModule().neutralizePullingDownTarget(pullingDownDrawable)
        } catch (t: Throwable) {
            TelegramHooksModule.currentModule().logError("Failed to neutralize pull-down target", t)
        }
        return result
    }
}

class ReplyLayoutHooker : XposedInterface.Hooker {
    companion object {
        private val replyMessage = ThreadLocal<Any?>()

        @Volatile
        private var replyMessageObjectField: Field? = null

        internal fun isReplyMessage(messageObject: Any?): Boolean = messageObject != null && replyMessage.get() === messageObject
    }

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val messageObject = chain.args.firstOrNull() ?: return chain.proceed()
        val field =
            replyMessageObjectField ?: findField(messageObject.javaClass, "replyMessageObject").also {
                replyMessageObjectField = it
            }
        val target = field.get(messageObject) ?: return chain.proceed()
        val previous = replyMessage.get()
        replyMessage.set(target)
        return try {
            chain.proceed()
        } finally {
            if (previous == null) {
                replyMessage.remove()
            } else {
                replyMessage.set(previous)
            }
        }
    }
}

class ReplyForwardedNameHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ReplyLayoutHooker.isReplyMessage(chain.thisObject)) {
            return null
        }
        return chain.proceed()
    }
}

class SecretMessageReadHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val chatActivity = chain.thisObject ?: return chain.proceed()
        val args = chain.args
        if (args.size != 2) {
            return chain.proceed()
        }
        val messageObject = args[0] ?: return chain.proceed()
        val readNow = args[1] as? Boolean ?: return chain.proceed()
        return try {
            TelegramHooksModule.currentModule().buildSelfDestructMediaReadAction(
                chatActivity,
                messageObject,
                readNow,
            )
        } catch (t: Throwable) {
            TelegramHooksModule.currentModule().logError(
                "Failed to override Telegram self-destruct media read flow",
                t,
            )
            chain.proceed()
        }
    }
}

class SecretMediaDeleteHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = null
}

class PreventDeleteTaskOnContentReadHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args.toTypedArray()
        TelegramHooksModule.currentModule().disarmSelfDestructDeleteTask(args)
        return chain.proceed(args)
    }
}

class PreventDeleteTaskOnSecretChatReadHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args.toTypedArray()
        TelegramHooksModule.currentModule().disarmSecretChatDeleteTask(args)
        return chain.proceed(args)
    }
}

class BlockCreateDeleteShowOnceTaskHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = 0L
}

class BlockDoDeleteShowOnceTaskHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = null
}

class FillMessageMenuHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val chatActivity = chain.thisObject ?: return result
        val args = chain.args.toTypedArray()
        TelegramHooksModule.currentModule().addSaveStickerToMessageMenu(chatActivity, args)
        TelegramHooksModule.currentModule().addPlusOneToMessageMenu(chatActivity, args)
        return result
    }
}

class CreateMenuHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (result !is Boolean || result != true) {
            return result
        }
        val chatActivity = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().attachLongPressToPlusOneMenuItem(chatActivity)
        return result
    }
}

class ProcessSelectedOptionHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args
        if (args.isNotEmpty() && args[0] is Int) {
            val option = args[0] as Int
            val chatActivity = chain.thisObject
            if (chatActivity != null && option == OPTION_SAVE_STICKER) {
                if (TelegramHooksModule.currentModule().handleSaveStickerOption(chatActivity, option)) {
                    return null
                }
            }
            if (option == OPTION_PLUS_ONE && chatActivity != null) {
                TelegramHooksModule.currentModule().forwardSelectedMessageToCurrentChat(chatActivity)
            }
        }
        return chain.proceed()
    }
}

class ContentPreviewGetInstanceHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val viewer = chain.proceed() ?: return null
        TelegramHooksModule.currentModule().installContentPreviewStickerMenuHook(viewer)
        return viewer
    }
}

class ContentPreviewShowSheetHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val runnable = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().patchContentPreviewStickerMenu(runnable)
        return result
    }
}

class ProfileCreateViewHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val profileActivity = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().installProfileIdDisplay(profileActivity)
        return result
    }
}

class ProfileUpdateDataHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val profileActivity = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().updateProfileIdDisplay(profileActivity)
        return result
    }
}

class ProfileLayoutHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val profileActivity = chain.thisObject ?: return result
        TelegramHooksModule.currentModule().syncProfileIdDisplay(profileActivity)
        return result
    }
}

class NoForwardsBypassHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = false
}

class ClearMessageNoForwardsHooker : XposedInterface.Hooker {
    companion object {
        @Volatile
        private var noForwardsDisabled = false

        @Volatile
        private var selfDestructDisabled = false

        @Volatile
        private var messageOwnerField: Field? = null

        @Volatile
        private var messageNoForwardsField: Field? = null

        @Volatile
        private var messageTtlField: Field? = null

        @Volatile
        private var messageDestroyTimeField: Field? = null

        @Volatile
        private var messageMediaField: Field? = null

        @Volatile
        private var messageMediaTtlSecondsField: Field? = null
    }

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (noForwardsDisabled && selfDestructDisabled) {
            return result
        }
        val messageObject = chain.thisObject ?: return result
        try {
            val ownerField =
                messageOwnerField ?: findField(messageObject.javaClass, "messageOwner").also {
                    messageOwnerField = it
                }
            val messageOwner = ownerField.get(messageObject) ?: return result

            if (!noForwardsDisabled) {
                try {
                    val noforwardsField =
                        messageNoForwardsField ?: findField(messageOwner.javaClass, "noforwards").also {
                            messageNoForwardsField = it
                        }
                    if (noforwardsField.getBoolean(messageOwner)) {
                        TelegramHooksModule.currentModule().markNoForwardsMessage(messageObject)
                    }
                    noforwardsField.setBoolean(messageOwner, false)
                } catch (t: Throwable) {
                    noForwardsDisabled = true
                    TelegramHooksModule.currentModule().logError(
                        "Failed to bypass Telegram noforwards message restriction",
                        t,
                    )
                }
            }

            if (!selfDestructDisabled) {
                try {
                    val mediaField =
                        messageMediaField ?: findField(messageOwner.javaClass, "media").also {
                            messageMediaField = it
                        }
                    val media = mediaField.get(messageOwner) ?: return result
                    val ttlSecondsField =
                        messageMediaTtlSecondsField ?: findField(media.javaClass, "ttl_seconds").also {
                            messageMediaTtlSecondsField = it
                        }
                    val ttlSeconds = ttlSecondsField.getInt(media)
                    if (ttlSeconds != 0) {
                        ttlSecondsField.setInt(media, 0)

                        val ttlField =
                            messageTtlField ?: findField(messageOwner.javaClass, "ttl").also {
                                messageTtlField = it
                            }
                        if (ttlField.getInt(messageOwner) != 0) {
                            ttlField.setInt(messageOwner, 0)
                        }

                        val destroyTimeField =
                            messageDestroyTimeField ?: findField(messageOwner.javaClass, "destroyTime").also {
                                messageDestroyTimeField = it
                            }
                        if (destroyTimeField.getInt(messageOwner) != 0) {
                            destroyTimeField.setInt(messageOwner, 0)
                        }
                    }
                } catch (t: Throwable) {
                    selfDestructDisabled = true
                    TelegramHooksModule.currentModule().logError(
                        "Failed to normalize Telegram self-destruct media flags",
                        t,
                    )
                }
            }
        } catch (t: Throwable) {
            noForwardsDisabled = true
            selfDestructDisabled = true
            TelegramHooksModule.currentModule().logError("Failed to patch Telegram message flags", t)
        }
        return result
    }
}
