package dev.xyenon.mxgram

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker

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
