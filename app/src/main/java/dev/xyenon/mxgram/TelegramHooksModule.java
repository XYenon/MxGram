package dev.xyenon.mxgram;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
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
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramHooksModule extends XposedModule {
  private static final String TAG = "MxGram";
  private static final String TARGET_PACKAGE = "org.telegram.messenger";
  private static final int OPTION_PLUS_ONE = 0x4D584701; // "MXG\u0001"
  private static final long PLUS_ONE_REPLY_TTL_MS = 60_000;

  private static final AtomicReference<PendingPlusOneReply> pendingPlusOneReply =
      new AtomicReference<>(null);

  private static volatile TelegramHooksModule instance;

  private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
  private final String processName;

  private final WeakHashMap<Object, Integer> plusOneMenuIndex = new WeakHashMap<>();

  private static final class PendingPlusOneReply {
    private final Object replyToMsg;
    private final int selectedMsgId;
    private final long createdAtUptimeMs;

    private PendingPlusOneReply(Object replyToMsg, int selectedMsgId) {
      this.replyToMsg = replyToMsg;
      this.selectedMsgId = selectedMsgId;
      this.createdAtUptimeMs = SystemClock.uptimeMillis();
    }
  }

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
    hookPlusOneForward(chatActivityClass);
  }

  private void hookPlusOneForward(Class<?> chatActivityClass) throws Exception {
    Method fillMessageMenu = null;
    for (Method method : chatActivityClass.getDeclaredMethods()) {
      if ("fillMessageMenu".equals(method.getName()) && method.getParameterCount() == 4) {
        fillMessageMenu = method;
        break;
      }
    }
    if (fillMessageMenu == null) {
      throw new IllegalStateException("ChatActivity.fillMessageMenu(...) not found");
    }
    fillMessageMenu.setAccessible(true);
    hook(fillMessageMenu, FillMessageMenuHooker.class);

    Method processSelectedOption =
        chatActivityClass.getDeclaredMethod("processSelectedOption", int.class);
    processSelectedOption.setAccessible(true);
    hook(processSelectedOption, ProcessSelectedOptionHooker.class);

    Method createMenu = null;
    for (Method method : chatActivityClass.getDeclaredMethods()) {
      if ("createMenu".equals(method.getName()) && method.getParameterCount() == 8) {
        createMenu = method;
        break;
      }
    }
    if (createMenu == null) {
      throw new IllegalStateException("ChatActivity.createMenu(...) not found");
    }
    createMenu.setAccessible(true);
    hook(createMenu, CreateMenuHooker.class);
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

  private static int getStaticIntFieldValue(Class<?> type, String name, int fallback) {
    try {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.getInt(null);
    } catch (Throwable t) {
      return fallback;
    }
  }

  private static int resolveTelegramDrawable(
      ClassLoader classLoader, String drawableName, int fallback) {
    if (classLoader == null) {
      return fallback;
    }
    try {
      Class<?> drawableClass =
          Class.forName("org.telegram.messenger.R$drawable", false, classLoader);
      Field field = drawableClass.getDeclaredField(drawableName);
      field.setAccessible(true);
      return field.getInt(null);
    } catch (Throwable t) {
      return fallback;
    }
  }

  private boolean canSendToCurrentConversation(Object chatActivity) {
    try {
      // If Telegram is showing the bottom overlay instead of the input field, we definitely can't
      // send right now.
      Object bottomChannelButtonsLayout =
          findField(chatActivity.getClass(), "bottomChannelButtonsLayout").get(chatActivity);
      if (bottomChannelButtonsLayout instanceof View) {
        // Telegram uses this overlay when the input field is not available.
        // NOTE: the normal state is usually INVISIBLE, not GONE.
        if (((View) bottomChannelButtonsLayout).getVisibility() == View.VISIBLE) {
          return false;
        }
      }

      // For user dialogs, blocked state is the most common reason why sending is disabled.
      try {
        if (findField(chatActivity.getClass(), "userBlocked").getBoolean(chatActivity)) {
          return false;
        }
      } catch (NoSuchFieldException ignored) {
        // Ignore.
      }

      Object currentChat = null;
      try {
        currentChat = findField(chatActivity.getClass(), "currentChat").get(chatActivity);
      } catch (NoSuchFieldException ignored) {
        // Ignore.
      }
      if (currentChat == null) {
        // Private chat / other modes: rely on the overlay checks above.
        return true;
      }

      ClassLoader classLoader = chatActivity.getClass().getClassLoader();
      if (classLoader == null) {
        return true;
      }
      Class<?> chatObjectClass =
          Class.forName("org.telegram.messenger.ChatObject", false, classLoader);

      // Not a member / left / kicked.
      if (invokeStaticBoolean(chatObjectClass, "isNotInChat", new Object[] {currentChat})) {
        return false;
      }

      // Channels where we can't post.
      if (!invokeStaticBoolean(chatObjectClass, "canWriteToChat", new Object[] {currentChat})) {
        return false;
      }

      // Muted by permissions / bans.
      if (!invokeStaticBoolean(chatObjectClass, "canSendMessages", new Object[] {currentChat})) {
        return false;
      }

      // Closed forum topic (unless we can manage it).
      Object forumTopic = null;
      try {
        forumTopic = findField(chatActivity.getClass(), "forumTopic").get(chatActivity);
      } catch (NoSuchFieldException ignored) {
        // Ignore.
      }
      if (forumTopic != null) {
        boolean closed = false;
        try {
          closed = findField(forumTopic.getClass(), "closed").getBoolean(forumTopic);
        } catch (NoSuchFieldException ignored) {
          // Ignore.
        }
        if (closed) {
          int currentAccount = 0;
          try {
            currentAccount =
                findField(chatActivity.getClass(), "currentAccount").getInt(chatActivity);
          } catch (NoSuchFieldException ignored) {
            // Ignore.
          }

          Boolean canManageTopic =
              invokeStaticBooleanOrNull(
                  chatObjectClass,
                  "canManageTopic",
                  new Object[] {currentAccount, currentChat, forumTopic});
          if (canManageTopic != null && !canManageTopic) {
            return false;
          }
        }
      }

      return true;
    } catch (Throwable t) {
      // Fail open: keep the option available if Telegram internals change.
      return true;
    }
  }

  private static boolean invokeStaticBoolean(Class<?> type, String name, Object[] args)
      throws Exception {
    Boolean result = invokeStaticBooleanOrNull(type, name, args);
    return Boolean.TRUE.equals(result);
  }

  private static Boolean invokeStaticBooleanOrNull(Class<?> type, String name, Object[] args)
      throws Exception {
    int paramCount = args != null ? args.length : 0;
    for (Method method : type.getDeclaredMethods()) {
      if (!name.equals(method.getName()) || method.getParameterCount() != paramCount) {
        continue;
      }
      // Disambiguate ChatObject.canManageTopic overloads: we want the one that takes a topic
      // object (not a long topicId).
      if ("canManageTopic".equals(name)
          && paramCount == 3
          && method.getParameterTypes()[2] == long.class) {
        continue;
      }
      method.setAccessible(true);
      Object result = method.invoke(null, args);
      if (result instanceof Boolean) {
        return (Boolean) result;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private void addPlusOneToMessageMenu(Object chatActivity, Object[] args) {
    if (!canSendToCurrentConversation(chatActivity)) {
      return;
    }
    if (args == null || args.length < 4) {
      return;
    }
    Object iconsRaw = args[1];
    Object itemsRaw = args[2];
    Object optionsRaw = args[3];
    if (!(iconsRaw instanceof java.util.ArrayList)
        || !(itemsRaw instanceof java.util.ArrayList)
        || !(optionsRaw instanceof java.util.ArrayList)) {
      return;
    }
    java.util.ArrayList<Integer> icons = (java.util.ArrayList<Integer>) iconsRaw;
    java.util.ArrayList<CharSequence> items = (java.util.ArrayList<CharSequence>) itemsRaw;
    java.util.ArrayList<Integer> options = (java.util.ArrayList<Integer>) optionsRaw;

    if (options.contains(OPTION_PLUS_ONE)) {
      plusOneMenuIndex.put(chatActivity, options.indexOf(OPTION_PLUS_ONE));
      return;
    }

    int optionForward = getStaticIntFieldValue(chatActivity.getClass(), "OPTION_FORWARD", 2);
    int forwardIndex = options.indexOf(optionForward);
    if (forwardIndex < 0) {
      return;
    }

    int insertIndex = Math.min(forwardIndex + 1, options.size());
    Integer forwardIcon = forwardIndex < icons.size() ? icons.get(forwardIndex) : 0;
    int plusIcon =
        resolveTelegramDrawable(
            chatActivity.getClass().getClassLoader(), "msg_filled_plus", forwardIcon);

    options.add(insertIndex, OPTION_PLUS_ONE);
    items.add(insertIndex, "+1");
    icons.add(Math.min(insertIndex, icons.size()), plusIcon);

    plusOneMenuIndex.put(chatActivity, insertIndex);
  }

  private void attachLongPressToPlusOneMenuItem(Object chatActivity) {
    Integer index = plusOneMenuIndex.remove(chatActivity);
    if (index == null) {
      return;
    }
    try {
      Object itemsRaw =
          findField(chatActivity.getClass(), "scrimPopupWindowItems").get(chatActivity);
      if (!(itemsRaw instanceof Object[])) {
        return;
      }
      Object[] items = (Object[]) itemsRaw;
      if (index < 0 || index >= items.length) {
        return;
      }
      Object item = items[index];
      if (!(item instanceof View)) {
        return;
      }
      View view = (View) item;
      view.setOnLongClickListener(v -> module().onPlusOneLongPressed(chatActivity, v));
    } catch (Throwable t) {
      logError("Failed to attach +1 long-press listener", t);
    }
  }

  private boolean onPlusOneLongPressed(Object chatActivity, View menuItemView) {
    try {
      PendingPlusOneReply pending = buildPendingPlusOneReply(chatActivity);
      pendingPlusOneReply.set(pending);
      // Reuse Telegram's normal click flow (it will close the menu and clear selection state).
      menuItemView.performClick();
      return true;
    } catch (Throwable t) {
      logError("Failed to handle +1 long-press", t);
      return false;
    }
  }

  private PendingPlusOneReply buildPendingPlusOneReply(Object chatActivity) {
    try {
      Object selectedObject =
          findField(chatActivity.getClass(), "selectedObject").get(chatActivity);
      if (selectedObject == null) {
        return null;
      }

      // Only support single-message replies for now.
      Object selectedObjectGroup =
          findField(chatActivity.getClass(), "selectedObjectGroup").get(chatActivity);
      if (selectedObjectGroup != null) {
        return null;
      }

      Method getReplyMsgId = selectedObject.getClass().getMethod("getReplyMsgId");
      int replyMsgId = (Integer) getReplyMsgId.invoke(selectedObject);
      if (replyMsgId <= 0) {
        return null;
      }

      // Only handle replies within the same dialog (reply_to_peer_id should be null).
      Object messageOwner =
          findField(selectedObject.getClass(), "messageOwner").get(selectedObject);
      if (messageOwner != null) {
        Object replyHeader = null;
        try {
          replyHeader = findField(messageOwner.getClass(), "reply_to").get(messageOwner);
        } catch (NoSuchFieldException ignored) {
          // Ignore.
        }
        if (replyHeader != null) {
          Object replyToPeerId = null;
          try {
            replyToPeerId = findField(replyHeader.getClass(), "reply_to_peer_id").get(replyHeader);
          } catch (NoSuchFieldException ignored) {
            // Ignore.
          }
          if (replyToPeerId != null) {
            return null;
          }
        }
      }

      Object replyToMsg =
          findField(selectedObject.getClass(), "replyMessageObject").get(selectedObject);
      if (replyToMsg == null) {
        return null;
      }

      int selectedMsgId =
          (Integer) selectedObject.getClass().getMethod("getId").invoke(selectedObject);
      return new PendingPlusOneReply(replyToMsg, selectedMsgId);
    } catch (Throwable t) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private void forwardSelectedMessageToCurrentChat(Object chatActivity) {
    try {
      Object selectedObject =
          findField(chatActivity.getClass(), "selectedObject").get(chatActivity);
      if (selectedObject == null) {
        return;
      }

      // Reply case: Telegram doesn't support replying with a forwarded (quoted) message.
      // When user long-presses +1 on a reply, we re-send the content as a normal message and
      // keep the reply target.
      PendingPlusOneReply pending = pendingPlusOneReply.getAndSet(null);
      if (pending != null
          && SystemClock.uptimeMillis() - pending.createdAtUptimeMs <= PLUS_ONE_REPLY_TTL_MS) {
        try {
          int selectedId =
              (Integer) selectedObject.getClass().getMethod("getId").invoke(selectedObject);
          if (selectedId == pending.selectedMsgId && pending.replyToMsg != null) {
            if (repeatSelectedMessageAsReply(chatActivity, selectedObject, pending.replyToMsg)) {
              return;
            }
          }
        } catch (Throwable ignored) {
          // Ignore.
        }
      }

      Object selectedObjectGroup =
          findField(chatActivity.getClass(), "selectedObjectGroup").get(chatActivity);

      java.util.ArrayList<Object> messages = new java.util.ArrayList<>();
      if (selectedObjectGroup != null) {
        Object groupMessages =
            findField(selectedObjectGroup.getClass(), "messages").get(selectedObjectGroup);
        if (groupMessages instanceof java.util.ArrayList) {
          messages.addAll((java.util.ArrayList<?>) groupMessages);
        }
      } else {
        messages.add(selectedObject);
      }
      if (messages.isEmpty()) {
        return;
      }

      // Prefer Telegram's internal sending path for forwarding inside the current chat.
      Method forwardMessages = null;
      for (Method method : chatActivity.getClass().getDeclaredMethods()) {
        if ("forwardMessages".equals(method.getName()) && method.getParameterCount() == 6) {
          forwardMessages = method;
          break;
        }
      }
      if (forwardMessages != null) {
        forwardMessages.setAccessible(true);
        forwardMessages.invoke(chatActivity, messages, false, false, true, 0, 0L);
        return;
      }

      // Fallback: show the forward panel (user still needs to tap send).
      Method showFieldPanelForForward =
          chatActivity
              .getClass()
              .getMethod("showFieldPanelForForward", boolean.class, java.util.ArrayList.class);
      showFieldPanelForForward.invoke(chatActivity, true, messages);
    } catch (Throwable t) {
      logError("Failed to +1 forward message", t);
    }
  }

  private boolean repeatSelectedMessageAsReply(
      Object chatActivity, Object selectedObject, Object replyToMsg) {
    if (replyToMsg == null || selectedObject == null) {
      return false;
    }
    try {
      long dialogId = (Long) chatActivity.getClass().getMethod("getDialogId").invoke(chatActivity);
      Object replyToTopMsg =
          chatActivity.getClass().getMethod("getThreadMessage").invoke(chatActivity);
      Object sendMessagesHelper =
          chatActivity.getClass().getMethod("getSendMessagesHelper").invoke(chatActivity);
      if (sendMessagesHelper == null) {
        return false;
      }

      boolean isSticker =
          Boolean.TRUE.equals(
              selectedObject.getClass().getMethod("isAnyKindOfSticker").invoke(selectedObject));
      if (isSticker) {
        Object document = selectedObject.getClass().getMethod("getDocument").invoke(selectedObject);
        if (document == null) {
          return false;
        }
        Method sendSticker = null;
        for (Method method : sendMessagesHelper.getClass().getDeclaredMethods()) {
          if ("sendSticker".equals(method.getName()) && method.getParameterCount() == 18) {
            sendSticker = method;
            break;
          }
        }
        if (sendSticker == null) {
          return false;
        }

        long monoForumPeer = 0L;
        try {
          monoForumPeer =
              (Long)
                  chatActivity.getClass().getMethod("getSendMonoForumPeerId").invoke(chatActivity);
        } catch (NoSuchMethodException ignored) {
          // Ignore.
        }
        Object suggestionParams = null;
        try {
          suggestionParams =
              chatActivity
                  .getClass()
                  .getMethod("getSendMessageSuggestionParams")
                  .invoke(chatActivity);
        } catch (NoSuchMethodException ignored) {
          // Ignore.
        }

        sendSticker.setAccessible(true);
        sendSticker.invoke(
            sendMessagesHelper,
            document,
            null,
            dialogId,
            replyToMsg,
            replyToTopMsg,
            null,
            null,
            null,
            true,
            0,
            0,
            false,
            null,
            null,
            0,
            0L,
            monoForumPeer,
            suggestionParams);
        return true;
      }

      Object messageOwner =
          findField(selectedObject.getClass(), "messageOwner").get(selectedObject);
      if (messageOwner == null) {
        return false;
      }
      Object messageRaw = findField(messageOwner.getClass(), "message").get(messageOwner);
      if (!(messageRaw instanceof String) || ((String) messageRaw).isEmpty()) {
        return false;
      }
      String message = (String) messageRaw;

      ClassLoader classLoader = chatActivity.getClass().getClassLoader();
      if (classLoader == null) {
        return false;
      }
      Class<?> sendMessageParamsClass =
          Class.forName(
              "org.telegram.messenger.SendMessagesHelper$SendMessageParams", false, classLoader);
      Method of = sendMessageParamsClass.getDeclaredMethod("of", String.class, long.class);
      of.setAccessible(true);
      Object params = of.invoke(null, message, dialogId);
      findField(params.getClass(), "replyToMsg").set(params, replyToMsg);
      findField(params.getClass(), "replyToTopMsg").set(params, replyToTopMsg);
      findField(params.getClass(), "notify").setBoolean(params, true);

      Method sendMessage = null;
      for (Method method : sendMessagesHelper.getClass().getDeclaredMethods()) {
        if ("sendMessage".equals(method.getName())
            && method.getParameterCount() == 1
            && sendMessageParamsClass.equals(method.getParameterTypes()[0])) {
          sendMessage = method;
          break;
        }
      }
      if (sendMessage == null) {
        return false;
      }
      sendMessage.setAccessible(true);
      sendMessage.invoke(sendMessagesHelper, params);
      return true;
    } catch (Throwable t) {
      logError("Failed to +1 repeat-reply message", t);
      return false;
    }
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

  @XposedHooker
  public static final class FillMessageMenuHooker implements XposedInterface.Hooker {
    @AfterInvocation
    public static void after(XposedInterface.AfterHookCallback callback) {
      TelegramHooksModule module = module();
      module.addPlusOneToMessageMenu(callback.getThisObject(), callback.getArgs());
    }
  }

  @XposedHooker
  public static final class CreateMenuHooker implements XposedInterface.Hooker {
    @AfterInvocation
    public static void after(XposedInterface.AfterHookCallback callback) {
      Object result = callback.getResult();
      if (!(result instanceof Boolean) || !Boolean.TRUE.equals(result)) {
        return;
      }
      module().attachLongPressToPlusOneMenuItem(callback.getThisObject());
    }
  }

  @XposedHooker
  public static final class ProcessSelectedOptionHooker implements XposedInterface.Hooker {
    @BeforeInvocation
    public static void before(XposedInterface.BeforeHookCallback callback) {
      Object[] args = callback.getArgs();
      if (args == null || args.length < 1 || !(args[0] instanceof Integer)) {
        return;
      }
      int option = (Integer) args[0];
      if (option != OPTION_PLUS_ONE) {
        return;
      }
      module().forwardSelectedMessageToCurrentChat(callback.getThisObject());
      // Do not skip: let Telegram close the menu & clear internal selection state.
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
