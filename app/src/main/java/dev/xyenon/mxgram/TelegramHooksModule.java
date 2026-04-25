package dev.xyenon.mxgram;

import android.content.Context;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TelegramHooksModule extends XposedModule {
  private static final String TAG = "MxGram";
  private static final String TARGET_PACKAGE = "org.telegram.messenger";
  private static volatile TelegramHooksModule instance;

  private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
  private final String processName;

  public TelegramHooksModule(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
    super(base, param);
    instance = this;
    processName = param.getProcessName();
  }

  @Override
  public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
    if (!TARGET_PACKAGE.equals(param.getPackageName())) {
      return;
    }
    if (!hooksInstalled.compareAndSet(false, true)) {
      return;
    }

    try {
      ClassLoader classLoader =
          param.getClassLoader() != null ? param.getClassLoader() : param.getDefaultClassLoader();
      if (classLoader == null) {
        throw new IllegalStateException("Telegram classloader is null");
      }
      installHooks(classLoader);
      logInfo("Telegram hooks installed in " + processName);
    } catch (Throwable t) {
      hooksInstalled.set(false);
      logError("Failed to install Telegram hooks", t);
    }
  }

  private void installHooks(ClassLoader classLoader) throws Exception {
    Class<?> chatActivityClass = Class.forName("org.telegram.ui.ChatActivity", false, classLoader);
    Class<?> chatGreetingsViewClass =
        Class.forName("org.telegram.ui.Components.ChatGreetingsView", false, classLoader);
    Class<?> pullingDownDrawableClass =
        Class.forName("org.telegram.ui.ChatPullingDownDrawable", false, classLoader);

    hookAnimateToNextChat(chatActivityClass);
    hookCreateView(chatActivityClass);
    hookGreetingStickerSend(chatGreetingsViewClass);
    hookSelectReaction(chatActivityClass);
    hookPullingDownTargets(pullingDownDrawableClass);
  }

  private void hookAnimateToNextChat(Class<?> chatActivityClass) throws NoSuchMethodException {
    Method method = chatActivityClass.getDeclaredMethod("animateToNextChat");
    method.setAccessible(true);
    hook(method, BlockAnimateToNextChatHooker.class);
  }

  private void hookCreateView(Class<?> chatActivityClass) throws NoSuchMethodException {
    Method method = chatActivityClass.getDeclaredMethod("createView", Context.class);
    method.setAccessible(true);
    hook(method, CreateViewHooker.class);
  }

  private void hookGreetingStickerSend(Class<?> chatGreetingsViewClass)
      throws NoSuchMethodException {
    Class<?> listenerInterface = null;
    for (Class<?> innerClass : chatGreetingsViewClass.getDeclaredClasses()) {
      if ("Listener".equals(innerClass.getSimpleName())) {
        listenerInterface = innerClass;
        break;
      }
    }
    if (listenerInterface == null) {
      throw new IllegalStateException("ChatGreetingsView.Listener not found");
    }

    Method method = chatGreetingsViewClass.getDeclaredMethod("setListener", listenerInterface);
    method.setAccessible(true);
    hook(method, DisableGreetingStickerHooker.class);
  }

  private void hookSelectReaction(Class<?> chatActivityClass) {
    for (Method method : chatActivityClass.getDeclaredMethods()) {
      if (!"selectReaction".equals(method.getName()) || method.getParameterCount() != 11) {
        continue;
      }
      method.setAccessible(true);
      hook(method, SelectReactionHooker.class);
      return;
    }
    throw new IllegalStateException("ChatActivity.selectReaction(...) not found");
  }

  private void hookPullingDownTargets(Class<?> pullingDownDrawableClass) {
    for (Method method : pullingDownDrawableClass.getDeclaredMethods()) {
      boolean isUpdateDialog =
          "updateDialog".equals(method.getName())
              && (method.getParameterCount() == 0 || method.getParameterCount() == 1);
      boolean isUpdateTopic =
          "updateTopic".equals(method.getName()) && method.getParameterCount() == 0;
      if (!isUpdateDialog && !isUpdateTopic) {
        continue;
      }
      method.setAccessible(true);
      hook(method, PullingDownTargetHooker.class);
    }
  }

  private void disableDoubleTapReaction(Object chatActivity, ClassLoader classLoader) {
    try {
      Field chatListViewField = findField(chatActivity.getClass(), "chatListView");
      Object chatListView = chatListViewField.get(chatActivity);
      if (chatListView == null) {
        return;
      }

      Class<?> recyclerListViewClass =
          Class.forName("org.telegram.ui.Components.RecyclerListView", false, classLoader);
      Field listenerField = findField(recyclerListViewClass, "onItemClickListenerExtended");
      Object originalListener = listenerField.get(chatListView);
      if (originalListener == null) {
        return;
      }
      if (Proxy.isProxyClass(originalListener.getClass())) {
        InvocationHandler handler = Proxy.getInvocationHandler(originalListener);
        if (handler instanceof DoubleTapDisablingHandler) {
          return;
        }
      }

      Class<?> listenerInterface =
          Class.forName(
              "org.telegram.ui.Components.RecyclerListView$OnItemClickListenerExtended",
              false,
              classLoader);
      Object proxy =
          Proxy.newProxyInstance(
              classLoader,
              new Class<?>[] {listenerInterface},
              new DoubleTapDisablingHandler(originalListener));

      Method setter =
          recyclerListViewClass.getDeclaredMethod("setOnItemClickListener", listenerInterface);
      setter.setAccessible(true);
      setter.invoke(chatListView, proxy);
    } catch (Throwable t) {
      logError("Failed to replace Telegram double-tap listener", t);
    }
  }

  private void neutralizePullingDownTarget(Object pullingDownDrawable) throws Exception {
    findField(pullingDownDrawable.getClass(), "emptyStub").setBoolean(pullingDownDrawable, true);
    findField(pullingDownDrawable.getClass(), "nextChat").set(pullingDownDrawable, null);
    findField(pullingDownDrawable.getClass(), "nextTopic").set(pullingDownDrawable, null);
    findField(pullingDownDrawable.getClass(), "nextDialogId").setLong(pullingDownDrawable, 0L);
  }

  private void clearGreetingStickerListener(Object chatGreetingsView) throws Exception {
    findField(chatGreetingsView.getClass(), "listener").set(chatGreetingsView, null);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(type.getName() + '#' + name);
  }

  private void logInfo(String message) {
    log(TAG + ": " + message);
  }

  private void logError(String message, Throwable throwable) {
    log(TAG + ": " + message, throwable);
  }

  private static TelegramHooksModule module() {
    TelegramHooksModule current = instance;
    if (current == null) {
      throw new IllegalStateException("Module instance is not ready");
    }
    return current;
  }

  @XposedHooker
  public static final class BlockAnimateToNextChatHooker implements XposedInterface.Hooker {
    @BeforeInvocation
    public static void before(XposedInterface.BeforeHookCallback callback) {
      callback.returnAndSkip(null);
    }
  }

  @XposedHooker
  public static final class CreateViewHooker implements XposedInterface.Hooker {
    @AfterInvocation
    public static void after(XposedInterface.AfterHookCallback callback) {
      TelegramHooksModule module = module();
      Object chatActivity = callback.getThisObject();
      module.disableDoubleTapReaction(chatActivity, chatActivity.getClass().getClassLoader());
    }
  }

  @XposedHooker
  public static final class SelectReactionHooker implements XposedInterface.Hooker {
    @BeforeInvocation
    public static void before(XposedInterface.BeforeHookCallback callback) {
      Object[] args = callback.getArgs();
      if (args.length > 7 && Boolean.TRUE.equals(args[7])) {
        callback.returnAndSkip(null);
      }
    }
  }

  @XposedHooker
  public static final class DisableGreetingStickerHooker implements XposedInterface.Hooker {
    @BeforeInvocation
    public static void before(XposedInterface.BeforeHookCallback callback) throws Throwable {
      module().clearGreetingStickerListener(callback.getThisObject());
      callback.returnAndSkip(null);
    }
  }

  @XposedHooker
  public static final class PullingDownTargetHooker implements XposedInterface.Hooker {
    @AfterInvocation
    public static void after(XposedInterface.AfterHookCallback callback) throws Throwable {
      module().neutralizePullingDownTarget(callback.getThisObject());
    }
  }

  private static final class DoubleTapDisablingHandler implements InvocationHandler {
    private final Object original;

    private DoubleTapDisablingHandler(Object original) {
      this.original = original;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if ("hasDoubleTap".equals(name)) {
        return false;
      }
      if ("onDoubleTap".equals(name)) {
        return null;
      }
      if (method.getDeclaringClass() == Object.class) {
        if ("toString".equals(name)) {
          return original + "[doubleTapDisabled]";
        }
        if ("hashCode".equals(name)) {
          return original.hashCode();
        }
        if ("equals".equals(name)) {
          return proxy == args[0];
        }
      }
      return method.invoke(original, args);
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj;
    }

    @Override
    public int hashCode() {
      return Objects.hash(original);
    }
  }
}
