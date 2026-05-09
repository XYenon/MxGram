package dev.xyenon.mxgram

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Field

@XposedHooker
class BlockAnimateToNextChatHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            callback.returnAndSkip(null)
        }
    }
}

@XposedHooker
class CreateViewHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val module = TelegramHooksModule.currentModule()
            val chatActivity = callback.thisObject ?: return
            module.disableDoubleTapReaction(chatActivity, chatActivity.javaClass.classLoader)
        }
    }
}

@XposedHooker
class SelectReactionHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val args = callback.args
            if (args.size > 7 && java.lang.Boolean.TRUE == args[7]) {
                callback.returnAndSkip(null)
            }
        }
    }
}

@XposedHooker
class DisableGreetingStickerHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        @Throws(Throwable::class)
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val chatGreetingsView = callback.thisObject ?: return
            TelegramHooksModule.currentModule().clearGreetingStickerListener(chatGreetingsView)
            callback.returnAndSkip(null)
        }
    }
}

@XposedHooker
class PullingDownTargetHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        @Throws(Throwable::class)
        fun after(callback: XposedInterface.AfterHookCallback) {
            val pullingDownDrawable = callback.thisObject ?: return
            TelegramHooksModule.currentModule().neutralizePullingDownTarget(pullingDownDrawable)
        }
    }
}

@XposedHooker
class SecretMessageReadHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val chatActivity = callback.thisObject ?: return
            val args = callback.args
            if (args.size != 2) {
                return
            }
            val messageObject = args[0] ?: return
            val readNow = args[1] as? Boolean ?: return
            try {
                val replacement =
                    TelegramHooksModule.currentModule().buildSelfDestructMediaReadAction(
                        chatActivity,
                        messageObject,
                        readNow,
                    )
                callback.returnAndSkip(replacement)
            } catch (t: Throwable) {
                TelegramHooksModule.currentModule().logError(
                    "Failed to override Telegram self-destruct media read flow",
                    t,
                )
            }
        }
    }
}

@XposedHooker
class SecretMediaDeleteHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            callback.returnAndSkip(null)
        }
    }
}

@XposedHooker
class PreventDeleteTaskOnContentReadHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            TelegramHooksModule.currentModule().disarmSelfDestructDeleteTask(callback.args)
        }
    }
}

@XposedHooker
class PreventDeleteTaskOnSecretChatReadHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            TelegramHooksModule.currentModule().disarmSecretChatDeleteTask(callback.args)
        }
    }
}

@XposedHooker
class BlockCreateDeleteShowOnceTaskHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            callback.returnAndSkip(0L)
        }
    }
}

@XposedHooker
class BlockDoDeleteShowOnceTaskHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            callback.returnAndSkip(null)
        }
    }
}

@XposedHooker
class FillMessageMenuHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val module = TelegramHooksModule.currentModule()
            val chatActivity = callback.thisObject ?: return
            module.addPlusOneToMessageMenu(chatActivity, callback.args)
        }
    }
}

@XposedHooker
class CreateMenuHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val result = callback.result
            if (result !is Boolean || result != true) {
                return
            }
            val chatActivity = callback.thisObject ?: return
            TelegramHooksModule.currentModule().attachLongPressToPlusOneMenuItem(chatActivity)
        }
    }
}

@XposedHooker
class ProcessSelectedOptionHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val args = callback.args
            if (args.isEmpty() || args[0] !is Int) {
                return
            }
            val option = args[0] as Int
            if (option != OPTION_PLUS_ONE) {
                return
            }
            val chatActivity = callback.thisObject ?: return
            TelegramHooksModule.currentModule().forwardSelectedMessageToCurrentChat(chatActivity)
            // Do not skip: let Telegram close the menu & clear internal selection state.
        }
    }
}

@XposedHooker
class ProfileCreateViewHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val profileActivity = callback.thisObject ?: return
            TelegramHooksModule.currentModule().installProfileIdDisplay(profileActivity)
        }
    }
}

@XposedHooker
class ProfileUpdateDataHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val profileActivity = callback.thisObject ?: return
            TelegramHooksModule.currentModule().updateProfileIdDisplay(profileActivity)
        }
    }
}

@XposedHooker
class ProfileLayoutHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            val profileActivity = callback.thisObject ?: return
            TelegramHooksModule.currentModule().syncProfileIdDisplay(profileActivity)
        }
    }
}

@XposedHooker
class NoForwardsBypassHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun before(callback: XposedInterface.BeforeHookCallback) {
            callback.returnAndSkip(false)
        }
    }
}

@XposedHooker
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

        @JvmStatic
        @AfterInvocation
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (noForwardsDisabled && selfDestructDisabled) {
                return
            }
            val messageObject = callback.thisObject ?: return
            try {
                val ownerField =
                    messageOwnerField ?: findField(messageObject.javaClass, "messageOwner").also {
                        messageOwnerField = it
                    }
                val messageOwner = ownerField.get(messageObject) ?: return

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
                        // If Telegram changes the underlying field names, disable the hook to avoid
                        // spamming logs during scrolling.
                        noForwardsDisabled = true
                        TelegramHooksModule.currentModule().logError(
                            "Failed to bypass Telegram noforwards message restriction",
                            t,
                        )
                    }
                }

                if (!selfDestructDisabled) {
                    try {
                        // Treat self-destruct media (ttl_seconds != 0) as regular media so the normal
                        // save/share UI becomes available.
                        val mediaField =
                            messageMediaField ?: findField(messageOwner.javaClass, "media").also {
                                messageMediaField = it
                            }
                        val media = mediaField.get(messageOwner) ?: return
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
        }
    }
}
