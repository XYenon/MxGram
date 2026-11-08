package dev.xyenon.mxgram

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.zip.ZipFile

internal object TelegramObfuscationResolver {
    private var isInitialized = false

    // Cached classes resolved by DexKit
    private var cachedChatActivityClass: Class<*>? = null
    private var cachedChatMessageCellClass: Class<*>? = null
    private var cachedMessageObjectClass: Class<*>? = null
    private var cachedMessagesControllerClass: Class<*>? = null
    private var cachedFileLoaderClass: Class<*>? = null
    private var cachedGroupedMessagesClass: Class<*>? = null
    private var cachedChatPullingDownDrawableClass: Class<*>? = null
    private var cachedChatGreetingsViewClass: Class<*>? = null
    private var cachedContentPreviewViewerClass: Class<*>? = null
    private var cachedRLottieDrawableClass: Class<*>? = null
    private var cachedBulletinFactoryClass: Class<*>? = null
    private var cachedRecyclerListViewClass: Class<*>? = null
    private var cachedActionBarPopupWindowClass: Class<*>? = null
    private var cachedActionBarMenuSubItemClass: Class<*>? = null

    // Cached MessageObject fields
    private var cachedMessageOwnerField: Field? = null
    private var cachedReplyMessageObjectField: Field? = null

    // Cached FileLoader methods
    private var cachedGetPathToAttachMethod: Method? = null
    private var cachedGetPathToMessageMethod: Method? = null

    // Cached MessagesController methods
    private var cachedCreateDeleteShowOnceTaskMethod: Method? = null
    private var cachedDoDeleteShowOnceTaskMethod: Method? = null
    private var cachedMarkMessageAsRead2Method: Method? = null
    private var cachedMarkMessageAsReadMethod: Method? = null
    private var cachedGetForwardedNameMethod: Method? = null
    private val cachedPeerNoForwardsMethods = ArrayList<Method>()

    // Cached ChatActivity methods and fields resolved by DexKit
    private var cachedCreateMenuMethod: Method? = null
    private var cachedProcessSelectedOptionMethod: Method? = null
    private var cachedAnimateToNextChatMethod: Method? = null
    private var cachedSendSecretMessageReadMethod: Method? = null
    private var cachedSendSecretMediaDeleteMethod: Method? = null
    private var cachedChatGetDialogIdMethod: Method? = null
    private var cachedChatGetCurrentUserMethod: Method? = null
    private var cachedChatGetCurrentChatMethod: Method? = null
    private var cachedChatCanSendMessageMethod: Method? = null
    private var cachedChatSendMonoForumPeerIdMethod: Method? = null
    private var cachedChatCurrentUserField: Field? = null
    private var cachedChatCurrentChatField: Field? = null
    private var cachedSelectedObjectField: Field? = null
    private var cachedSelectedObjectGroupField: Field? = null
    private var cachedSelectedObjectToEditCaptionField: Field? = null
    private var cachedScrimPopupWindowField: Field? = null
    private var cachedScrimPopupWindowItemsField: Field? = null
    private var cachedChatUserInfoField: Field? = null
    private var cachedChatForumTopicField: Field? = null

    // Cached ChatPullingDownDrawable methods and fields resolved by DexKit
    private var cachedPullingDownEmptyStubField: Field? = null
    private var cachedPullingDownNextChatField: Field? = null
    private var cachedPullingDownNextTopicField: Field? = null
    private var cachedPullingDownNextDialogIdField: Field? = null
    private var cachedPullingDownImageReceiverField: Field? = null
    private val cachedPullingDownUpdateMethods = mutableListOf<Method>()

    // Cached ContentPreviewViewer methods and fields resolved by DexKit
    private var cachedContentPreviewShowSheetRunnableField: Field? = null
    private var cachedContentPreviewCurrentContentTypeField: Field? = null
    private var cachedContentPreviewCurrentDocumentField: Field? = null
    private var cachedContentPreviewMenuVisibleField: Field? = null
    private var cachedContentPreviewIsVisibleField: Field? = null
    private var cachedContentPreviewIsPhotoEditorField: Field? = null
    private var cachedContentPreviewPopupWindowField: Field? = null
    private var cachedContentPreviewMoveYField: Field? = null
    private var cachedContentPreviewKeyboardHeightField: Field? = null
    private var cachedContentPreviewCloseWithMenuMethod: Method? = null
    private var cachedContentPreviewCloseMethod: Method? = null
    private var cachedContentPreviewDismissPopupWindowMethod: Method? = null
    private var cachedContentPreviewGetInstanceMethod: Method? = null
    private var cachedContentPreviewCurrentAccountField: Field? = null
    private var cachedContentPreviewResourcesProviderField: Field? = null
    private var cachedContentPreviewCurrentPreviewCellField: Field? = null
    private var cachedContentPreviewUnlockPremiumViewField: Field? = null
    private var cachedContentPreviewStickerEmojiLayoutField: Field? = null
    private var cachedContentPreviewDrawEffectField: Field? = null
    private var cachedContentPreviewCloseOnDismissField: Field? = null

    // Cached RLottieDrawable methods and fields resolved by DexKit
    private var cachedRLottieMetaDataField: Field? = null
    private var cachedRLottiePrepareForGenerateCacheMethod: Method? = null
    private var cachedRLottieReleaseForGenerateCacheMethod: Method? = null
    private var cachedRLottieGetNextFrameMethod: Method? = null
    private var cachedRLottieRecycleMethod: Method? = null

    // Cached ProfileActivity methods resolved by DexKit
    private var cachedProfileUpdateDataMethod: Method? = null
    private var cachedProfileNeedLayoutMethod: Method? = null
    private var cachedProfileSetAvatarExpandProgressMethod: Method? = null
    private var cachedProfileGetDialogIdMethod: Method? = null

    // Cached ProfileActivity fields resolved by DexKit
    private var cachedProfileOnlineTextViewField: Field? = null
    private var cachedProfileNameTextViewField: Field? = null
    private var cachedProfileChatInfoField: Field? = null
    private var cachedProfileUserInfoField: Field? = null

    // Cached MessagesController methods and fields resolved by DexKit
    private var cachedMessagesControllerGetUserMethod: Method? = null
    private var cachedMessagesControllerGetChatMethod: Method? = null
    private var cachedMessagesControllerBlockedPeersField: Field? = null

    fun initialize(
        classLoader: ClassLoader,
        hostSourceDir: String,
        moduleSourceDir: String,
        moduleNativeLibDir: String,
        log: (String) -> Unit,
        logErr: (String, Throwable) -> Unit,
    ) {
        if (isInitialized) return
        isInitialized = true

        val isUnobfuscated =
            try {
                Class.forName("org.telegram.ui.ChatActivity", false, classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        if (isUnobfuscated) {
            log("Telegram is unobfuscated, DexKit scan skipped")
            return
        }

        val loaded = loadDexKitNative(moduleNativeLibDir, moduleSourceDir, log, logErr)
        if (!loaded) {
            logErr("Failed to load libdexkit.so; obfuscated hooks cannot be resolved", IllegalStateException("libdexkit.so load failed"))
            return
        }

        val startTime = System.currentTimeMillis()
        try {
            log("Starting DexKit scan on host APK: $hostSourceDir")
            DexKitBridge.create(hostSourceDir).use { bridge ->
                log("DexKit loaded DEX files count: ${bridge.getDexNum()}")
                scanWithDexKit(bridge, classLoader, log)
            }
            log("DexKit scan completed in ${System.currentTimeMillis() - startTime}ms")
        } catch (t: Throwable) {
            logErr("DexKit scanning failed", t)
        }
    }

    private fun loadDexKitNative(
        nativeLibDir: String,
        sourceDir: String,
        log: (String) -> Unit,
        logErr: (String, Throwable) -> Unit,
    ): Boolean {
        try {
            System.loadLibrary("dexkit")
            log("Loaded libdexkit.so via System.loadLibrary")
            return true
        } catch (t: Throwable) {
            log("System.loadLibrary(dexkit) failed: ${t.message}")
        }
        try {
            val soFile = File(nativeLibDir, "libdexkit.so")
            if (soFile.exists()) {
                System.load(soFile.absolutePath)
                log("Loaded libdexkit.so from nativeLibDir: $soFile")
                return true
            }
        } catch (t: Throwable) {
            log("Loading from nativeLibDir failed: ${t.message}")
        }
        try {
            val apkFile = File(sourceDir)
            if (apkFile.exists()) {
                val abi =
                    android.os.Build.SUPPORTED_ABIS
                        .firstOrNull() ?: "arm64-v8a"
                ZipFile(apkFile).use { zip ->
                    val entry =
                        zip.getEntry("lib/$abi/libdexkit.so")
                            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("libdexkit.so") }
                    if (entry != null) {
                        val tempFile = File.createTempFile("libdexkit-", ".so")
                        tempFile.deleteOnExit()
                        zip.getInputStream(entry).use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        System.load(tempFile.absolutePath)
                        log("Loaded libdexkit.so from extracted temp file: ${tempFile.absolutePath}")
                        return true
                    }
                }
            }
        } catch (t: Throwable) {
            logErr("Extracting and loading libdexkit.so failed", t)
        }
        return false
    }

    private fun MethodData.usedFields(
        owner: String,
        type: String,
        access: FieldUsingType,
    ): List<FieldData> =
        usingFields
            .filter { it.field.declaredClassName == owner && it.field.typeName == type && it.usingType == access }
            .map { it.field }
            .distinct()

    private fun List<FieldData>.resolveField(
        classLoader: ClassLoader,
        label: String,
        log: (String) -> Unit,
    ): Field? =
        singleOrNull()?.getFieldInstance(classLoader)?.also {
            it.isAccessible = true
            log("DexKit resolved $label -> ${it.name}")
        } ?: run {
            log("DexKit unresolved $label: $size candidates")
            null
        }

    internal fun <T> resolveFeature(
        label: String,
        log: (String) -> Unit,
        resolve: () -> T,
    ): T? =
        try {
            resolve()
        } catch (t: Throwable) {
            log("DexKit resolution failed for $label: $t")
            null
        }

    private fun scanWithDexKit(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        log: (String) -> Unit,
    ) {
        // 1. ChatActivity: in org.telegram.ui, exclude subpackages, contains string "open menu msg_id="
        val chatActivityData =
            resolveFeature("ChatActivity class", log) {
                bridge
                    .findClass {
                        searchPackages("org.telegram.ui")
                        excludePackages("org.telegram.ui.Components", "org.telegram.ui.Cells", "org.telegram.ui.ActionBar")
                        matcher {
                            usingStrings("open menu msg_id=")
                        }
                    }.singleOrNull()
                    ?.also {
                        cachedChatActivityClass = it.getInstance(classLoader)
                        log("DexKit resolved ChatActivity -> ${it.name}")
                    }
            }

        // 2. ChatMessageCell: in org.telegram.ui.Cells, has setMessageObjectInternal
        resolveFeature("ChatMessageCell", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.Cells")
                    matcher {
                        addMethod {
                            name("setMessageObjectInternal")
                        }
                    }
                }.firstOrNull()
                ?.let { data ->
                    cachedChatMessageCellClass = data.getInstance(classLoader)
                    log("DexKit resolved ChatMessageCell -> ${data.name}")
                }
        }

        cachedChatMessageCellClass?.let { cellClass ->
            val method = cellClass.declaredMethods.firstOrNull { it.name == "setMessageObjectInternal" }
            if (method != null && method.parameterCount >= 1) {
                cachedMessageObjectClass = method.parameterTypes[0]
                log("DexKit resolved MessageObject -> ${cachedMessageObjectClass?.name}")
            }
        }
        cachedChatActivityClass?.let { chatClass ->
            chatClass.methods.firstOrNull { it.name == "getMessagesController" }?.let {
                cachedMessagesControllerClass = it.returnType
                log("DexKit resolved MessagesController -> ${cachedMessagesControllerClass?.name}")
            }
            chatClass.methods.firstOrNull { it.name == "getFileLoader" }?.let {
                cachedFileLoaderClass = it.returnType
                log("DexKit resolved FileLoader -> ${cachedFileLoaderClass?.name}")
            }
        }

        // 3. ChatPullingDownDrawable: in org.telegram.ui, implements NotificationCenterDelegate,
        // contains strings "paintChatComposeBackground" and "50_50".
        resolveFeature("ChatPullingDownDrawable", log) {
            val pullingDownData =
                bridge
                    .findClass {
                        searchPackages("org.telegram.ui")
                        excludePackages("org.telegram.ui.Components", "org.telegram.ui.Cells", "org.telegram.ui.ActionBar")
                        matcher {
                            superClass("java.lang.Object")
                            usingStrings("paintChatComposeBackground")
                        }
                    }.singleOrNull() ?: bridge
                    .findClass {
                        searchPackages("org.telegram.ui")
                        excludePackages("org.telegram.ui.Components", "org.telegram.ui.Cells", "org.telegram.ui.ActionBar")
                        matcher {
                            superClass("java.lang.Object")
                            addInterface("org.telegram.messenger.NotificationCenter\$NotificationCenterDelegate")
                            usingStrings("50_50")
                        }
                    }.singleOrNull()
            val pullingDownCls = pullingDownData?.getInstance(classLoader)
            if (pullingDownCls != null) {
                cachedChatPullingDownDrawableClass = pullingDownCls
                log("DexKit resolved ChatPullingDownDrawable -> ${pullingDownCls.name}")

                val owner = pullingDownData.name
                val nextChat = pullingDownData.fields.singleOrNull { it.typeName == "org.telegram.tgnet.TLRPC\$Chat" }
                cachedPullingDownNextChatField = listOfNotNull(nextChat).resolveField(classLoader, "ChatPullingDownDrawable.nextChat", log)
                cachedPullingDownNextTopicField =
                    pullingDownData.fields
                        .filter { it.typeName == "org.telegram.tgnet.TLRPC\$TL_forumTopic" }
                        .resolveField(classLoader, "ChatPullingDownDrawable.nextTopic", log)
                cachedPullingDownImageReceiverField =
                    pullingDownData.fields
                        .filter { it.typeName == "org.telegram.messenger.ImageReceiver" }
                        .resolveField(classLoader, "ChatPullingDownDrawable.imageReceiver", log)
                val updateMethods =
                    nextChat
                        ?.writers
                        .orEmpty()
                        .filter {
                            it.declaredClassName == owner && it.returnTypeName == "void" && !it.isConstructor &&
                                (it.paramCount == 0 || it.paramTypeNames == listOf("org.telegram.tgnet.TLRPC\$Chat"))
                        }.distinct()
                cachedPullingDownNextDialogIdField =
                    updateMethods
                        .singleOrNull { it.paramCount == 1 }
                        ?.usedFields(owner, "long", FieldUsingType.Write)
                        ?.resolveField(classLoader, "ChatPullingDownDrawable.nextDialogId", log)

                val drawMethod =
                    pullingDownData.methods.firstOrNull {
                        it.returnTypeName == "void" && it.paramCount >= 1 &&
                            it.paramTypeNames[0] == "android.graphics.Canvas"
                    }
                val drawReads = drawMethod?.usedFields(owner, "boolean", FieldUsingType.Read).orEmpty()
                val updateWrites = updateMethods.flatMap { it.usedFields(owner, "boolean", FieldUsingType.Write) }.toSet()
                cachedPullingDownEmptyStubField =
                    pullingDownData.methods
                        .singleOrNull {
                            it.paramCount == 0 && it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers)
                        }?.usedFields(owner, "boolean", FieldUsingType.Read)
                        ?.resolveField(classLoader, "ChatPullingDownDrawable.emptyStub", log)
                        ?: drawReads
                            .filter { it in updateWrites }
                            .groupBy { it.name }
                            .maxByOrNull { it.value.size }
                            ?.value
                            ?.firstOrNull()
                            ?.let { listOf(it) }
                            ?.resolveField(classLoader, "ChatPullingDownDrawable.emptyStub", log)
                cachedPullingDownUpdateMethods.clear()
                cachedPullingDownUpdateMethods.addAll(updateMethods.map { it.getMethodInstance(classLoader) })
                log(
                    "DexKit resolved ${cachedPullingDownUpdateMethods.size} ChatPullingDownDrawable update methods: " +
                        cachedPullingDownUpdateMethods.map { it.name },
                )
            }
        }

        // The image filter alone also occurs in unrelated views.
        resolveFeature("ChatGreetingsView", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.Components")
                    matcher {
                        superClass("android.widget.LinearLayout")
                        usingStrings("256_256")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedChatGreetingsViewClass = data.getInstance(classLoader)
                    log("DexKit resolved ChatGreetingsView -> ${data.name}")
                }
        }

        // 5. ContentPreviewViewer: in org.telegram.ui, contains string "90_90_b"
        resolveFeature("ContentPreviewViewer", log) {
            val contentPreviewData =
                bridge
                    .findClass {
                        searchPackages("org.telegram.ui")
                        excludePackages("org.telegram.ui.Components", "org.telegram.ui.Cells", "org.telegram.ui.ActionBar")
                        matcher {
                            usingStrings("90_90_b")
                        }
                    }.singleOrNull()
            val cpCls = contentPreviewData?.getInstance(classLoader)
            if (cpCls != null) {
                cachedContentPreviewViewerClass = cpCls
                log("DexKit resolved ContentPreviewViewer -> ${cpCls.name}")
                val owner = contentPreviewData.name
                val methods = contentPreviewData.methods
                val open =
                    methods.singleOrNull {
                        "90_90_b" in it.usingStrings && it.returnTypeName == "void" &&
                            "org.telegram.tgnet.TLRPC\$Document" in it.paramTypeNames
                    }
                val document = contentPreviewData.fields.singleOrNull { it.typeName == "org.telegram.tgnet.TLRPC\$Document" }
                cachedContentPreviewCurrentDocumentField =
                    listOfNotNull(document).resolveField(classLoader, "ContentPreviewViewer.currentDocument", log)
                cachedContentPreviewShowSheetRunnableField =
                    cpCls.declaredFields
                        .singleOrNull {
                            Runnable::class.java.isAssignableFrom(it.type) && Modifier.isFinal(it.modifiers) &&
                                !Modifier.isStatic(it.modifiers)
                        }?.also { it.isAccessible = true }
                val sheetRun =
                    cachedContentPreviewShowSheetRunnableField?.type?.let { type ->
                        findMethodOrNull(type, "run")?.let { bridge.getMethodData(it) }
                    }
                cachedContentPreviewCurrentContentTypeField =
                    open
                        ?.usedFields(owner, "int", FieldUsingType.Write)
                        ?.resolveField(classLoader, "ContentPreviewViewer.currentContentType", log)
                cachedContentPreviewPopupWindowField =
                    cpCls.declaredFields
                        .singleOrNull {
                            android.widget.PopupWindow::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers)
                        }?.also { it.isAccessible = true }
                val popupType = cachedContentPreviewPopupWindowField?.type?.name
                val dismiss =
                    methods.singleOrNull {
                        it.paramCount == 0 && it.returnTypeName == "void" &&
                            it.invokes.any { call ->
                                call.name == "dismiss" && call.declaredClassName == popupType
                            }
                    }
                val closeZeroParam =
                    document?.writers.orEmpty().filter {
                        it.declaredClassName == owner && it.paramCount == 0 && it.returnTypeName == "void"
                    }
                val close =
                    if (closeZeroParam.size == 1) {
                        closeZeroParam.first()
                    } else {
                        closeZeroParam.singleOrNull { candidate ->
                            candidate.invokes.any { call -> call.name == "cancelRunOnUIThread" }
                        }
                    }
                val dismissWrites = dismiss?.usedFields(owner, "boolean", FieldUsingType.Write).orEmpty()
                val menu =
                    close
                        ?.usedFields(owner, "boolean", FieldUsingType.Read)
                        .orEmpty()
                        .intersect(dismissWrites.toSet())
                        .toList()
                cachedContentPreviewMenuVisibleField = menu.resolveField(classLoader, "ContentPreviewViewer.menuVisible", log)
                cachedContentPreviewCloseOnDismissField =
                    (dismiss?.usedFields(owner, "boolean", FieldUsingType.Read).orEmpty() - menu.toSet())
                        .resolveField(classLoader, "ContentPreviewViewer.closeOnDismiss", log)
                val visible =
                    close
                        ?.usedFields(
                            owner,
                            "boolean",
                            FieldUsingType.Write,
                        ).orEmpty()
                        .intersect(open?.usedFields(owner, "boolean", FieldUsingType.Write).orEmpty().toSet())
                        .toList()
                cachedContentPreviewIsVisibleField = visible.resolveField(classLoader, "ContentPreviewViewer.isVisible", log)
                val container = findContentPreviewContainerViewField(cpCls)
                val draw = findMethodOrNull(container.type, "onDraw", android.graphics.Canvas::class.java)?.let { bridge.getMethodData(it) }
                cachedContentPreviewDrawEffectField =
                    (
                        draw
                            ?.usedFields(owner, "boolean", FieldUsingType.Read)
                            .orEmpty()
                            .intersect(open?.usedFields(owner, "boolean", FieldUsingType.Write).orEmpty().toSet()) -
                            (visible.toSet() + menu.toSet())
                    ).toList()
                        .resolveField(classLoader, "ContentPreviewViewer.drawEffect", log)
                val touchWrites =
                    methods
                        .filter { "android.view.MotionEvent" in it.paramTypeNames }
                        .flatMap { it.usedFields(owner, "float", FieldUsingType.Write) }
                        .toSet()
                val constructorFloats =
                    methods
                        .filter { it.isConstructor }
                        .flatMap {
                            it.usedFields(
                                owner,
                                "float",
                                FieldUsingType.Write,
                            )
                        }.toSet()
                cachedContentPreviewMoveYField =
                    draw
                        ?.usedFields(owner, "float", FieldUsingType.Read)
                        ?.filter { it in touchWrites }
                        ?.filter { it in constructorFloats }
                        ?.resolveField(classLoader, "ContentPreviewViewer.moveY", log)
                val activitySetter = methods.singleOrNull { "kbd_height" in it.usingStrings }
                val activityInts = activitySetter?.usedFields(owner, "int", FieldUsingType.Write).orEmpty()
                val constructorInts =
                    methods
                        .filter { it.isConstructor }
                        .flatMap {
                            it.usedFields(
                                owner,
                                "int",
                                FieldUsingType.Write,
                            )
                        }.toSet()
                cachedContentPreviewKeyboardHeightField =
                    activityInts
                        .filter { it in constructorInts }
                        .resolveField(classLoader, "ContentPreviewViewer.keyboardHeight", log)
                cachedContentPreviewCurrentAccountField =
                    activityInts
                        .filter { it !in constructorInts }
                        .resolveField(classLoader, "ContentPreviewViewer.currentAccount", log)
                val delegateSetter =
                    methods.singleOrNull {
                        it.returnTypeName == "void" && it.paramCount in 1..2 && !it.isConstructor &&
                            (it.paramCount == 1 || it.paramTypeNames.last() == "boolean") &&
                            it.paramTypeNames.first().let { type ->
                                runCatching { Class.forName(type, false, classLoader).isInterface }.getOrDefault(false)
                            }
                    }
                cachedContentPreviewIsPhotoEditorField =
                    delegateSetter
                        ?.usedFields(owner, "boolean", FieldUsingType.Write)
                        ?.filter { it in sheetRun?.usedFields(owner, "boolean", FieldUsingType.Read).orEmpty() }
                        ?.resolveField(classLoader, "ContentPreviewViewer.isPhotoEditor", log)
                cachedContentPreviewResourcesProviderField =
                    contentPreviewData.fields
                        .filter {
                            it.typeName in open?.paramTypeNames.orEmpty() &&
                                runCatching { Class.forName(it.typeName, false, classLoader).isInterface }.getOrDefault(false)
                        }.resolveField(classLoader, "ContentPreviewViewer.resourcesProvider", log)
                cachedContentPreviewCurrentPreviewCellField =
                    contentPreviewData.fields
                        .filter { it.typeName == "android.view.View" }
                        .resolveField(classLoader, "ContentPreviewViewer.currentPreviewCell", log)
                cachedContentPreviewStickerEmojiLayoutField =
                    contentPreviewData.fields
                        .filter { it.typeName == "android.text.StaticLayout" }
                        .resolveField(classLoader, "ContentPreviewViewer.stickerEmojiLayout", log)
                try {
                    val unlockPremiumClass =
                        bridge
                            .findClass {
                                searchPackages("org.telegram.ui")
                                matcher {
                                    superClass("android.widget.FrameLayout")
                                    addMethod {
                                        name("<init>")
                                        paramCount(3)
                                        paramTypes("android.content.Context", "int")
                                    }
                                }
                            }.firstOrNull { cd ->
                                contentPreviewData.fields.any { it.typeName == cd.name }
                            }
                    val candidates = contentPreviewData.fields.filter { it.typeName == unlockPremiumClass?.name }
                    val stickerUnlock =
                        candidates.firstOrNull { field ->
                            field.writers.any { w -> w.paramCount == 0 && w.returnTypeName == "void" }
                        } ?: candidates.firstOrNull()
                    cachedContentPreviewUnlockPremiumViewField =
                        listOfNotNull(stickerUnlock).resolveField(classLoader, "ContentPreviewViewer.unlockPremiumView", log)
                } catch (t: Throwable) {
                    log("DexKit resolution failed for ContentPreviewViewer.unlockPremiumView: $t")
                }
                cachedContentPreviewDismissPopupWindowMethod = dismiss?.getMethodInstance(classLoader)
                cachedContentPreviewCloseMethod = close?.getMethodInstance(classLoader)
                cachedContentPreviewCloseWithMenuMethod =
                    methods
                        .singleOrNull {
                            it.paramCount == 0 && it.returnTypeName == "void" && close != null && dismiss != null &&
                                close in it.invokes && dismiss in it.invokes
                        }?.getMethodInstance(classLoader)
                cachedContentPreviewGetInstanceMethod =
                    cpCls.declaredMethods.singleOrNull {
                        Modifier.isStatic(it.modifiers) && it.returnType == cpCls && it.parameterCount == 0
                    }
                log("DexKit resolved ContentPreviewViewer.getInstance -> ${cachedContentPreviewGetInstanceMethod?.name}")
            }
        }

        // The old "+ LottieDrawable " diagnostic is commented out in current Telegram.
        resolveFeature("RLottieDrawable", log) {
            val rlottieData =
                bridge
                    .findClass {
                        searchPackages("org.telegram.ui.Components")
                        matcher {
                            superClass("android.graphics.drawable.BitmapDrawable")
                            usingStrings("RLottieDrawable nativePtr == 0 ")
                        }
                    }.singleOrNull()
            if (rlottieData != null) {
                val rCls = rlottieData.getInstance(classLoader)
                cachedRLottieDrawableClass = rCls
                log("DexKit resolved RLottieDrawable -> ${rlottieData.name}")

                cachedRLottieMetaDataField =
                    rCls.declaredFields
                        .singleOrNull {
                            it.type == IntArray::class.java && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
                        }?.also {
                            it.isAccessible = true
                            log("DexKit resolved RLottieDrawable.metaData -> ${it.name}")
                        }

                cachedRLottieGetNextFrameMethod =
                    rCls.methods
                        .singleOrNull {
                            it.parameterCount == 1 &&
                                it.parameterTypes[0] == android.graphics.Bitmap::class.java &&
                                it.returnType == java.lang.Integer.TYPE
                        }?.also {
                            it.isAccessible = true
                            log("DexKit resolved RLottieDrawable.getNextFrame -> ${it.name}")
                        }

                val nativeClass = resolveClassOrNull(classLoader, "org.telegram.ui.Components.RLottieNative")
                val cacheNativeField =
                    if (nativeClass != null) {
                        bridge
                            .findMethod {
                                searchInClass(listOf(rlottieData))
                                matcher {
                                    paramTypes("android.graphics.Bitmap")
                                    returnType("int")
                                }
                            }.singleOrNull()
                            ?.usingFields
                            ?.map { it.field }
                            ?.singleOrNull { it.typeName == nativeClass.name }
                    } else {
                        null
                    }
                bridge
                    .findMethod {
                        searchInClass(listOf(rlottieData))
                        matcher {
                            paramCount(0)
                            returnType("void")
                            cacheNativeField?.let { field -> addUsingField { name(field.name) } }
                            addInvoke {
                                if (nativeClass != null) {
                                    declaredClass(nativeClass.name)
                                    returnType(nativeClass.name)
                                } else {
                                    name("createFromFile")
                                }
                            }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedRLottiePrepareForGenerateCacheMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved RLottieDrawable.prepareForGenerateCache -> ${methodData.name}")
                    }

                bridge
                    .findMethod {
                        searchInClass(listOf(rlottieData))
                        matcher {
                            paramCount(0)
                            returnType("void")
                            cacheNativeField?.let { field -> addUsingField { name(field.name) } }
                            addInvoke {
                                if (nativeClass != null) {
                                    declaredClass(nativeClass.name)
                                    paramCount(0)
                                    returnType("void")
                                } else {
                                    name("destroy")
                                }
                            }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedRLottieReleaseForGenerateCacheMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved RLottieDrawable.releaseForGenerateCache -> ${methodData.name}")
                    }
                bridge
                    .findMethod {
                        searchInClass(listOf(rlottieData))
                        matcher {
                            paramTypes("boolean")
                            returnType("void")
                            addCaller { name("finalize") }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedRLottieRecycleMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved RLottieDrawable.recycle -> ${methodData.name}")
                    }
            }
        }

        // FileSavedHintLinked belongs to the FileType enum, not the factory.
        resolveFeature("BulletinFactory", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.Components")
                    matcher {
                        usingStrings("MessagePinnedHint", "MessageUnpinnedHint")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedBulletinFactoryClass = data.getInstance(classLoader)
                    log("DexKit resolved BulletinFactory -> ${data.name}")
                }
        }

        // 8. RecyclerListView: in org.telegram.ui.Components, contains string "initializeScrollbars"
        resolveFeature("RecyclerListView", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.Components")
                    matcher {
                        usingStrings("initializeScrollbars")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedRecyclerListViewClass = data.getInstance(classLoader)
                    log("DexKit resolved RecyclerListView -> ${data.name}")
                }
        }

        // 9. ActionBarPopupWindow: in org.telegram.ui.ActionBar, extends PopupWindow, contains string "mOnScrollChangedListener"
        resolveFeature("ActionBarPopupWindow", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.ActionBar")
                    matcher {
                        superClass("android.widget.PopupWindow")
                        usingStrings("mOnScrollChangedListener")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedActionBarPopupWindowClass = data.getInstance(classLoader)
                    log("DexKit resolved ActionBarPopupWindow -> ${data.name}")
                }
        }

        // 10. ActionBarMenuSubItem: in org.telegram.ui.ActionBar, extends FrameLayout, contains string "android.widget.CheckBox"
        resolveFeature("ActionBarMenuSubItem", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.ActionBar")
                    matcher {
                        superClass("android.widget.FrameLayout")
                        usingStrings("android.widget.CheckBox")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedActionBarMenuSubItemClass = data.getInstance(classLoader)
                    log("DexKit resolved ActionBarMenuSubItem -> ${data.name}")
                }
        }

        resolveFeature("Chat sending", log) {
            if (chatActivityData != null) {
                val methods = chatActivityData.methods
                cachedChatCanSendMessageMethod =
                    methods
                        .firstOrNull {
                            it.paramCount == 0 && it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers) &&
                                it.invokes.isNotEmpty() && it.invokes.all { call -> call.name == "getVisibility" } &&
                                it.usingFields.any { u -> u.field.typeName.endsWith("EncryptedChat") }
                        }?.getMethodInstance(classLoader)
                cachedChatSendMonoForumPeerIdMethod =
                    methods
                        .firstOrNull {
                            it.paramCount == 0 && it.returnTypeName == "long" && !Modifier.isStatic(it.modifiers) &&
                                it.usingFields.any { u -> u.field.name == "saved_peer_id" }
                        }?.getMethodInstance(classLoader)
                log("DexKit resolved ChatActivity.canSendMessage -> ${cachedChatCanSendMessageMethod?.name}")
                log("DexKit resolved ChatActivity.getSendMonoForumPeerId -> ${cachedChatSendMonoForumPeerIdMethod?.name}")
            }
        }

        // --- Resolve ChatActivity Methods and Fields ---
        resolveFeature("ChatActivity", log) {
            if (chatActivityData != null) {
                // ChatActivity.createMenu: contains string "open menu msg_id="
                bridge
                    .findMethod {
                        searchInClass(listOf(chatActivityData))
                        matcher {
                            usingStrings("open menu msg_id=")
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedCreateMenuMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ChatActivity.createMenu -> ${methodData.name}")
                    }

                // ChatActivity.processSelectedOption: contains string "forward_into_channel"
                bridge
                    .findMethod {
                        searchInClass(listOf(chatActivityData))
                        matcher {
                            usingStrings("forward_into_channel")
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedProcessSelectedOptionMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ChatActivity.processSelectedOption -> ${methodData.name}")
                        val msgObjName = resolveMessageObject(classLoader).name
                        val grpClassName =
                            resolveGroupedMessages(classLoader)?.name ?: "org.telegram.messenger.MessageObject\$GroupedMessages"
                        cachedSelectedObjectField =
                            listOfNotNull(
                                methodData
                                    .usedFields(chatActivityData.name, msgObjName, FieldUsingType.Read)
                                    .maxByOrNull { it.readers.size },
                            ).resolveField(classLoader, "ChatActivity.selectedObject", log)
                                ?: findFieldOrNull(cachedChatActivityClass ?: Any::class.java, "selectedObject")
                        cachedSelectedObjectGroupField =
                            methodData
                                .usedFields(
                                    chatActivityData.name,
                                    grpClassName,
                                    FieldUsingType.Read,
                                ).resolveField(classLoader, "ChatActivity.selectedObjectGroup", log)
                                ?: findFieldOrNull(cachedChatActivityClass ?: Any::class.java, "selectedObjectGroup")
                    }

                // ChatActivity.animateToNextChat: contains strings "pulled" and "dialog_folder_id", void return
                bridge
                    .findMethod {
                        searchInClass(listOf(chatActivityData))
                        matcher {
                            returnType("void")
                            usingStrings("pulled", "dialog_folder_id")
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedAnimateToNextChatMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ChatActivity.animateToNextChat -> ${methodData.name}")
                    }

                // R8 can narrow Runnable returns and turn the delete method into a static method.
                cachedChatActivityClass?.let { cls ->
                    cachedSendSecretMessageReadMethod = resolveSendSecretMessageReadMethod(cls)
                    log("Resolved ChatActivity.sendSecretMessageRead -> ${cachedSendSecretMessageReadMethod?.name}")
                    cachedSendSecretMediaDeleteMethod = resolveSendSecretMediaDeleteMethod(cls)
                    log("Resolved ChatActivity.sendSecretMediaDelete -> ${cachedSendSecretMediaDeleteMethod?.name}")
                }

                // ChatActivity fields by type:
                val chatCls = cachedChatActivityClass
                if (chatCls != null) {
                    // userInfo field
                    cachedChatUserInfoField =
                        chatCls.declaredFields.singleOrNull { it.type.name.endsWith("UserFull") }?.also {
                            it.isAccessible = true
                            log("DexKit resolved ChatActivity.userInfo -> ${it.name}")
                        }
                    // forumTopic field
                    cachedChatForumTopicField =
                        chatCls.declaredFields.singleOrNull { it.type.name.endsWith("TL_forumTopic") }?.also {
                            it.isAccessible = true
                            log("DexKit resolved ChatActivity.forumTopic -> ${it.name}")
                        }
                    // scrimPopupWindow field (type ActionBarPopupWindow)
                    cachedActionBarPopupWindowClass?.let { popupCls ->
                        cachedScrimPopupWindowField =
                            chatCls.declaredFields.singleOrNull { it.type == popupCls }?.also {
                                it.isAccessible = true
                                log("DexKit resolved ChatActivity.scrimPopupWindow -> ${it.name}")
                            }
                    }
                    // scrimPopupWindowItems field (type ActionBarMenuSubItem[])
                    cachedActionBarMenuSubItemClass?.let { itemCls ->
                        cachedScrimPopupWindowItemsField =
                            chatCls.declaredFields
                                .singleOrNull {
                                    it.type.isArray && it.type.componentType == itemCls
                                }?.also {
                                    it.isAccessible = true
                                    log("DexKit resolved ChatActivity.scrimPopupWindowItems -> ${it.name}")
                                }
                    }
                    // currentUser field and getCurrentUser method
                    bridge
                        .findMethod {
                            searchInClass(listOf(chatActivityData))
                            matcher {
                                paramCount(0)
                                returnType("User", StringMatchType.EndsWith)
                            }
                        }.singleOrNull()
                        ?.let { methodData ->
                            cachedChatGetCurrentUserMethod = methodData.getMethodInstance(classLoader)
                            log("DexKit resolved ChatActivity.getCurrentUser -> ${methodData.name}")
                            cachedChatCurrentUserField =
                                methodData
                                    .usedFields(chatActivityData.name, "org.telegram.tgnet.TLRPC\$User", FieldUsingType.Read)
                                    .resolveField(classLoader, "ChatActivity.currentUser", log)
                        }
                    if (cachedChatCurrentUserField == null) {
                        cachedChatCurrentUserField =
                            chatCls.declaredFields
                                .singleOrNull {
                                    (it.type.name.endsWith("TLRPC\$User") || it.type.name.endsWith(".User")) &&
                                        !Modifier.isStatic(it.modifiers)
                                }?.also {
                                    it.isAccessible = true
                                    log("DexKit resolved ChatActivity.currentUser (fallback) -> ${it.name}")
                                }
                    }

                    // currentChat field and getCurrentChat method
                    bridge
                        .findMethod {
                            searchInClass(listOf(chatActivityData))
                            matcher {
                                paramCount(0)
                                returnType("Chat", StringMatchType.EndsWith)
                            }
                        }.singleOrNull()
                        ?.let { methodData ->
                            cachedChatGetCurrentChatMethod = methodData.getMethodInstance(classLoader)
                            log("DexKit resolved ChatActivity.getCurrentChat -> ${methodData.name}")
                            cachedChatCurrentChatField =
                                methodData
                                    .usedFields(chatActivityData.name, "org.telegram.tgnet.TLRPC\$Chat", FieldUsingType.Read)
                                    .resolveField(classLoader, "ChatActivity.currentChat", log)
                        }
                    if (cachedChatCurrentChatField == null) {
                        cachedChatCurrentChatField =
                            chatCls.declaredFields
                                .singleOrNull {
                                    (it.type.name.endsWith("TLRPC\$Chat") || it.type.name.endsWith(".Chat")) &&
                                        !Modifier.isStatic(it.modifiers)
                                }?.also {
                                    it.isAccessible = true
                                    log("DexKit resolved ChatActivity.currentChat (fallback) -> ${it.name}")
                                }
                    }

                    // The secret-read path uses the conversation ID, whereas fragment creation
                    // reads many unrelated long fields (topics, migrations, reply IDs).
                    val dialogId =
                        cachedSendSecretMessageReadMethod
                            ?.let { bridge.getMethodData(it) }
                            ?.usedFields(chatActivityData.name, "long", FieldUsingType.Read)
                            ?.singleOrNull()
                    cachedChatGetDialogIdMethod =
                        chatActivityData.methods
                            .singleOrNull {
                                it.paramCount == 0 && it.returnTypeName == "long" && !Modifier.isStatic(it.modifiers) &&
                                    dialogId != null &&
                                    it.usedFields(chatActivityData.name, "long", FieldUsingType.Read) == listOf(dialogId) &&
                                    it.invokes.isEmpty()
                            }?.getMethodInstance(classLoader)
                    log("DexKit resolved ChatActivity.getDialogId -> ${cachedChatGetDialogIdMethod?.name}")
                }
            }
        }

        // --- Resolve ProfileActivity Methods and Fields ---
        resolveFeature("ProfileActivity", log) {
            val profileActivityData = bridge.getClassData("org.telegram.ui.ProfileActivity")

            if (profileActivityData != null) {
                // ProfileActivity.updateProfileData: contains "Members" and "OnlineCount", (boolean) -> void
                bridge
                    .findMethod {
                        searchInClass(listOf(profileActivityData))
                        matcher {
                            addParamType("boolean")
                            returnType("void")
                            usingStrings("Members", "OnlineCount")
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedProfileUpdateDataMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ProfileActivity.updateProfileData -> ${methodData.name}")
                    }

                // ProfileActivity.needLayout: invokes View.setTranslationX and View.setTranslationY, (boolean) -> void
                bridge
                    .findMethod {
                        searchInClass(listOf(profileActivityData))
                        matcher {
                            addParamType("boolean")
                            returnType("void")
                            addInvoke { name("setTranslationX") }
                            addInvoke { name("setTranslationY") }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedProfileNeedLayoutMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ProfileActivity.needLayout -> ${methodData.name}")
                    }

                // ProfileActivity.setAvatarExpandProgress: invokes View.setScaleX, (float) -> void
                bridge
                    .findMethod {
                        searchInClass(listOf(profileActivityData))
                        matcher {
                            addParamType("float")
                            returnType("void")
                            addInvoke { name("setScaleX") }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedProfileSetAvatarExpandProgressMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ProfileActivity.setAvatarExpandProgress -> ${methodData.name}")
                    }

                // Field-use order does not identify Bundle keys (topic_id precedes dialog_id).
                // On obfuscated builds ProfileIdDisplay reads the original arguments instead.

                // ProfileActivity fields by type:
                val profileCls = Class.forName("org.telegram.ui.ProfileActivity", false, classLoader)
                cachedProfileChatInfoField =
                    profileCls.declaredFields.singleOrNull { it.type.name.endsWith("ChatFull") }?.also {
                        it.isAccessible = true
                        log("DexKit resolved ProfileActivity.chatInfo -> ${it.name}")
                    }
                cachedProfileUserInfoField =
                    profileCls.declaredFields.singleOrNull { it.type.name.endsWith("UserFull") }?.also {
                        it.isAccessible = true
                        log("DexKit resolved ProfileActivity.userInfo -> ${it.name}")
                    }

                // SimpleTextView resolution in org.telegram.ui.ActionBar
                val simpleTextViewData =
                    bridge
                        .findClass {
                            searchPackages("org.telegram.ui.ActionBar")
                            matcher {
                                superClass("android.view.View")
                                addMethod {
                                    name("getText")
                                    paramCount(0)
                                    returnType("java.lang.CharSequence")
                                }
                                addMethod {
                                    name("getTextWidth")
                                    paramCount(0)
                                }
                            }
                        }.firstOrNull()

                if (simpleTextViewData != null) {
                    for (fd in profileActivityData.fields) {
                        if (Modifier.isStatic(fd.modifiers)) continue
                        val ft = bridge.getClassData(fd.typeName) ?: continue

                        var curr: org.luckypray.dexkit.result.ClassData? = ft
                        var inheritsSimpleTextView = false
                        while (curr != null && curr.name != "java.lang.Object" && curr.name != "android.view.View") {
                            if (curr.name == simpleTextViewData.name) {
                                inheritsSimpleTextView = true
                                break
                            }
                            curr = curr.superClass
                        }

                        if (inheritsSimpleTextView) {
                            if (ft.superClass?.name == simpleTextViewData.name) {
                                cachedProfileNameTextViewField =
                                    profileCls.getDeclaredField(fd.name).also {
                                        it.isAccessible = true
                                        log("DexKit resolved ProfileActivity.nameTextView -> ${fd.name}")
                                    }
                            } else {
                                cachedProfileOnlineTextViewField =
                                    profileCls.getDeclaredField(fd.name).also {
                                        it.isAccessible = true
                                        log("DexKit resolved ProfileActivity.onlineTextView -> ${fd.name}")
                                    }
                            }
                        }
                    }
                }

                // ProfileActivity.getDialogId(): () -> long, non-static
                bridge
                    .findMethod {
                        searchInClass(listOf(profileActivityData))
                        matcher {
                            paramCount(0)
                            returnType("long")
                        }
                    }.firstOrNull { !Modifier.isStatic(it.modifiers) }
                    ?.let { methodData ->
                        cachedProfileGetDialogIdMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ProfileActivity.getDialogId -> ${methodData.name}")
                    }
            }

            // 9. MessageObject methods
            resolveFeature("MessageObject methods", log) {
                cachedMessageObjectClass?.let { msgCls ->
                    bridge
                        .findMethod {
                            matcher {
                                declaredClass(msgCls.name)
                                paramCount(0)
                                returnType("java.lang.String")
                                addUsingField {
                                    name("fwd_from")
                                }
                            }
                        }.firstOrNull()
                        ?.let { methodData ->
                            cachedGetForwardedNameMethod = methodData.getMethodInstance(classLoader)
                            log("DexKit resolved MessageObject.getForwardedName -> ${methodData.name}")
                        }
                }
            }

            // 10. MessagesController methods
            resolveFeature("MessagesController methods", log) {
                cachedMessagesControllerClass?.let { mcCls ->
                    val chatClass = Class.forName("org.telegram.tgnet.TLRPC\$Chat", false, classLoader)
                    val userFullClass = Class.forName("org.telegram.tgnet.TLRPC\$UserFull", false, classLoader)
                    val chatMethod =
                        mcCls.declaredMethods.firstOrNull { m ->
                            m.parameterCount == 1 && m.parameterTypes[0] == chatClass && m.returnType == java.lang.Boolean.TYPE
                        } ?: mcCls.declaredMethods.firstOrNull { m ->
                            m.name == "isChatNoForwards" && m.parameterCount == 1
                        }
                    val userMethod =
                        mcCls.declaredMethods.firstOrNull { m ->
                            m.parameterCount == 1 && m.parameterTypes[0] == userFullClass && m.returnType == java.lang.Boolean.TYPE
                        } ?: mcCls.declaredMethods.firstOrNull { m ->
                            m.name == "isUserNoForwards" && m.parameterCount == 1
                        }

                    if (chatMethod != null) {
                        bridge
                            .findMethod {
                                matcher {
                                    declaredClass(mcCls.name)
                                    paramTypes("long")
                                    returnType("boolean")
                                    addInvoke {
                                        name(chatMethod.name)
                                    }
                                }
                            }.mapNotNull { it.getMethodInstance(classLoader) }
                            .forEach { m ->
                                if (!cachedPeerNoForwardsMethods.contains(m)) {
                                    cachedPeerNoForwardsMethods.add(m)
                                    log("DexKit resolved MessagesController.isPeerNoForwards (invoking ${chatMethod.name}) -> ${m.name}")
                                }
                            }
                    }
                    if (userMethod != null) {
                        bridge
                            .findMethod {
                                matcher {
                                    declaredClass(mcCls.name)
                                    paramTypes("long")
                                    returnType("boolean")
                                    addInvoke {
                                        name(userMethod.name)
                                    }
                                }
                            }.mapNotNull { it.getMethodInstance(classLoader) }
                            .forEach { m ->
                                if (!cachedPeerNoForwardsMethods.contains(m)) {
                                    cachedPeerNoForwardsMethods.add(m)
                                    log("DexKit resolved MessagesController.isPeerNoForwards (invoking ${userMethod.name}) -> ${m.name}")
                                }
                            }
                    }

                    // markMessageAsRead: 3 primitive args, returns void, invokes getSecretChatHelper
                    bridge
                        .findMethod {
                            matcher {
                                declaredClass(mcCls.name)
                                paramCount(3)
                                returnType("void")
                                addInvoke {
                                    name("getSecretChatHelper")
                                }
                            }
                        }.firstOrNull()
                        ?.let { methodData ->
                            cachedMarkMessageAsReadMethod = methodData.getMethodInstance(classLoader)
                            log("DexKit resolved MessagesController.markMessageAsRead -> ${methodData.name}")
                        }

                    // doDeleteShowOnceTask: the other 3-primitive-arg (long, int, long) void method in MessagesController
                    val candidate3ParamMethods =
                        mcCls.declaredMethods.filter { m ->
                            m.parameterCount == 3 && m.returnType == java.lang.Void.TYPE &&
                                m.parameterTypes[0] == java.lang.Long.TYPE &&
                                m.parameterTypes[1] == java.lang.Integer.TYPE &&
                                m.parameterTypes[2] == java.lang.Long.TYPE
                        }
                    val doDeleteMethod = candidate3ParamMethods.firstOrNull { it != cachedMarkMessageAsReadMethod }
                    if (doDeleteMethod != null) {
                        cachedDoDeleteShowOnceTaskMethod = doDeleteMethod
                        log("DexKit resolved MessagesController.doDeleteShowOnceTask -> ${doDeleteMethod.name}")
                    }

                    cachedMessagesControllerGetUserMethod =
                        mcCls.declaredMethods
                            .firstOrNull {
                                it.returnType.name.endsWith("TLRPC\$User") && it.parameterCount == 1 &&
                                    (it.parameterTypes[0] == java.lang.Long::class.java || it.parameterTypes[0] == java.lang.Long.TYPE)
                            }?.also {
                                it.isAccessible = true
                                log("DexKit resolved MessagesController.getUser -> ${it.name}")
                            }

                    cachedMessagesControllerGetChatMethod =
                        mcCls.declaredMethods
                            .firstOrNull {
                                it.returnType.name.endsWith("TLRPC\$Chat") && it.parameterCount == 1 &&
                                    (it.parameterTypes[0] == java.lang.Long::class.java || it.parameterTypes[0] == java.lang.Long.TYPE)
                            }?.also {
                                it.isAccessible = true
                                log("DexKit resolved MessagesController.getChat -> ${it.name}")
                            }

                    bridge
                        .findMethod {
                            matcher {
                                declaredClass(mcCls.name)
                                addInvoke {
                                    declaredClass("org.telegram.tgnet.TLRPC\$TL_contacts_unblock")
                                    name("<init>")
                                }
                            }
                        }.firstOrNull()
                        ?.usedFields(
                            mcCls.name,
                            "org.telegram.messenger.support.LongSparseIntArray",
                            FieldUsingType.Read,
                        )?.resolveField(classLoader, "MessagesController.blockePeers", log)
                        ?.let { cachedMessagesControllerBlockedPeersField = it }
                }
            }
        }
    }

    fun resolveClass(
        classLoader: ClassLoader,
        vararg candidateNames: String,
    ): Class<*> {
        for (name in candidateNames) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: ClassNotFoundException) {
            }
        }
        throw ClassNotFoundException("None of the candidate classes found: ${candidateNames.joinToString(", ")}")
    }

    fun resolveClassOrNull(
        classLoader: ClassLoader,
        vararg candidateNames: String,
    ): Class<*>? {
        for (name in candidateNames) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: ClassNotFoundException) {
            }
        }
        return null
    }

    fun resolveChatActivity(classLoader: ClassLoader): Class<*> =
        cachedChatActivityClass ?: resolveClass(classLoader, "org.telegram.ui.ChatActivity")

    fun resolveProfileActivity(classLoader: ClassLoader): Class<*> = resolveClass(classLoader, "org.telegram.ui.ProfileActivity")

    fun resolveChatMessageCell(classLoader: ClassLoader): Class<*> =
        cachedChatMessageCellClass ?: resolveClass(classLoader, "org.telegram.ui.Cells.ChatMessageCell")

    fun resolveMessageObject(classLoader: ClassLoader): Class<*> {
        cachedMessageObjectClass?.let { return it }
        try {
            val cls = classLoader.loadClass("org.telegram.messenger.MessageObject")
            cachedMessageObjectClass = cls
            return cls
        } catch (_: ClassNotFoundException) {
        }
        val cellClass = resolveChatMessageCell(classLoader)
        val method = cellClass.declaredMethods.firstOrNull { it.name == "setMessageObjectInternal" }
        if (method != null && method.parameterCount >= 1) {
            cachedMessageObjectClass = method.parameterTypes[0]
            return cachedMessageObjectClass!!
        }
        val getMethod = cellClass.methods.firstOrNull { it.name == "getMessageObject" }
        if (getMethod != null && getMethod.returnType != java.lang.Void.TYPE) {
            cachedMessageObjectClass = getMethod.returnType
            return cachedMessageObjectClass!!
        }
        val fallback = resolveClass(classLoader, "org.telegram.messenger.MessageObject")
        cachedMessageObjectClass = fallback
        return fallback
    }

    private fun resolveMessageObjectOrNull(classLoader: ClassLoader): Class<*>? =
        runCatching { resolveMessageObject(classLoader) }.getOrNull()

    fun resolveMessagesController(classLoader: ClassLoader): Class<*> {
        cachedMessagesControllerClass?.let { return it }
        try {
            val cls = classLoader.loadClass("org.telegram.messenger.MessagesController")
            cachedMessagesControllerClass = cls
            return cls
        } catch (_: ClassNotFoundException) {
        }
        val chatActivity = resolveChatActivity(classLoader)
        val method = chatActivity.methods.firstOrNull { it.name == "getMessagesController" }
        if (method != null && method.returnType != java.lang.Void.TYPE) {
            cachedMessagesControllerClass = method.returnType
            return cachedMessagesControllerClass!!
        }
        val fallback = resolveClass(classLoader, "org.telegram.messenger.MessagesController")
        cachedMessagesControllerClass = fallback
        return fallback
    }

    fun resolveFileLoader(classLoader: ClassLoader): Class<*> {
        cachedFileLoaderClass?.let { return it }
        try {
            val cls = classLoader.loadClass("org.telegram.messenger.FileLoader")
            cachedFileLoaderClass = cls
            return cls
        } catch (_: ClassNotFoundException) {
        }
        val chatActivity = resolveChatActivity(classLoader)
        val method = chatActivity.methods.firstOrNull { it.name == "getFileLoader" }
        if (method != null && method.returnType != java.lang.Void.TYPE) {
            cachedFileLoaderClass = method.returnType
            return cachedFileLoaderClass!!
        }
        val fallback = resolveClass(classLoader, "org.telegram.messenger.FileLoader")
        cachedFileLoaderClass = fallback
        return fallback
    }

    fun resolveGroupedMessages(classLoader: ClassLoader): Class<*>? {
        cachedGroupedMessagesClass?.let { return it }
        try {
            val cls = classLoader.loadClass("org.telegram.messenger.MessageObject\$GroupedMessages")
            cachedGroupedMessagesClass = cls
            return cls
        } catch (_: ClassNotFoundException) {
        }
        val msgObjClass = resolveMessageObjectOrNull(classLoader)
        if (msgObjClass != null) {
            for (declaredClass in msgObjClass.declaredClasses) {
                if (declaredClass.declaredFields.any { it.name == "messages" && it.type == ArrayList::class.java }) {
                    cachedGroupedMessagesClass = declaredClass
                    return declaredClass
                }
            }
            val chatActivity = runCatching { resolveChatActivity(classLoader) }.getOrNull()
            val method =
                chatActivity?.declaredMethods?.firstOrNull { m ->
                    m.parameterCount == 1 && m.parameterTypes[0] == msgObjClass &&
                        !m.returnType.isPrimitive && m.returnType != java.lang.Void.TYPE &&
                        m.returnType.name.startsWith("org.telegram.messenger.")
                }
            if (method != null) {
                cachedGroupedMessagesClass = method.returnType
                return cachedGroupedMessagesClass
            }
        }
        val chatActivity = runCatching { resolveChatActivity(classLoader) }.getOrNull()
        if (chatActivity != null) {
            findFieldOrNull(chatActivity, "selectedObjectGroup")?.type?.let {
                cachedGroupedMessagesClass = it
                return it
            }
        }
        return null
    }

    fun resolveChatPullingDownDrawable(classLoader: ClassLoader): Class<*> =
        cachedChatPullingDownDrawableClass ?: resolveClass(classLoader, "org.telegram.ui.ChatPullingDownDrawable")

    fun resolveChatGreetingsView(classLoader: ClassLoader): Class<*> =
        cachedChatGreetingsViewClass ?: resolveClass(classLoader, "org.telegram.ui.Components.ChatGreetingsView")

    fun resolveChatGreetingsListener(
        classLoader: ClassLoader,
        greetingsViewClass: Class<*>,
    ): Class<*> {
        val inner = greetingsViewClass.declaredClasses.singleOrNull { it.simpleName == "Listener" }
        if (inner != null) {
            return inner
        }
        val setListenerMethod =
            greetingsViewClass.declaredMethods.singleOrNull {
                it.name == "setListener" && it.parameterCount == 1 && it.parameterTypes[0].isInterface
            } ?: greetingsViewClass.declaredMethods.singleOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].isInterface
            }
        if (setListenerMethod != null) {
            return setListenerMethod.parameterTypes[0]
        }
        return resolveClass(classLoader, "org.telegram.ui.Components.ChatGreetingsView\$Listener")
    }

    fun resolveContentPreviewViewer(classLoader: ClassLoader): Class<*> =
        cachedContentPreviewViewerClass ?: resolveClass(classLoader, "org.telegram.ui.ContentPreviewViewer")

    fun resolveActionBarPopupWindow(classLoader: ClassLoader): Class<*> =
        cachedActionBarPopupWindowClass ?: resolveClass(classLoader, "org.telegram.ui.ActionBar.ActionBarPopupWindow")

    fun resolveActionBarMenuSubItem(classLoader: ClassLoader): Class<*> =
        cachedActionBarMenuSubItemClass ?: resolveClass(classLoader, "org.telegram.ui.ActionBar.ActionBarMenuSubItem")

    fun resolveRLottieDrawable(classLoader: ClassLoader): Class<*> =
        cachedRLottieDrawableClass ?: resolveClass(classLoader, "org.telegram.ui.Components.RLottieDrawable")

    fun resolveBulletinFactory(classLoader: ClassLoader): Class<*> =
        cachedBulletinFactoryClass ?: resolveClass(classLoader, "org.telegram.ui.Components.BulletinFactory")

    fun resolveRecyclerListView(classLoader: ClassLoader): Class<*> =
        cachedRecyclerListViewClass ?: resolveClass(classLoader, "org.telegram.ui.Components.RecyclerListView")

    // --- Method Resolvers ---

    fun resolveFillMessageMenuMethod(chatActivityClass: Class<*>): Method {
        findDeclaredMethodByNameAndArity(chatActivityClass, "fillMessageMenu", 4, 5)?.let { return it }
        val classLoader = chatActivityClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val msgObjClass = resolveMessageObjectOrNull(classLoader)
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size in 4..5 &&
                ((msgObjClass != null && params[0] == msgObjClass) || params[0].name.endsWith("MessageObject")) &&
                params.drop(1).all { it == ArrayList::class.java }
        } ?: throw NoSuchMethodException("ChatActivity.fillMessageMenu(...) not found")
    }

    fun resolveProcessSelectedOptionMethod(chatActivityClass: Class<*>): Method {
        cachedProcessSelectedOptionMethod?.let { return it }
        findMethodOrNull(chatActivityClass, "processSelectedOption", java.lang.Integer.TYPE)?.let { return it }
        throw NoSuchMethodException("ChatActivity.processSelectedOption(int) not uniquely resolved")
    }

    fun resolveCreateMenuMethod(chatActivityClass: Class<*>): Method {
        cachedCreateMenuMethod?.let { return it }
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            method.returnType == java.lang.Boolean.TYPE &&
                params.size == 8 &&
                View::class.java.isAssignableFrom(params[0]) &&
                params[1] == java.lang.Boolean.TYPE &&
                params[2] == java.lang.Boolean.TYPE &&
                params[3] == java.lang.Float.TYPE &&
                params[4] == java.lang.Float.TYPE &&
                params.drop(5).all { type -> type == java.lang.Boolean.TYPE }
        } ?: throw NoSuchMethodException("ChatActivity.createMenu(...) not found")
    }

    fun resolveSelectReactionMethod(chatActivityClass: Class<*>): Method {
        findMethodOrNull(chatActivityClass, "selectReaction")?.let { return it }
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            method.returnType == java.lang.Void.TYPE &&
                params.size == 11 &&
                params[4] == java.lang.Float.TYPE &&
                params[5] == java.lang.Float.TYPE &&
                params.drop(7).all { type -> type == java.lang.Boolean.TYPE }
        } ?: throw NoSuchMethodException("ChatActivity.selectReaction(...) not found")
    }

    fun resolveSendSecretMessageReadMethod(chatActivityClass: Class<*>): Method {
        cachedSendSecretMessageReadMethod?.let { return it }
        findDeclaredMethodByPredicate(chatActivityClass) { method ->
            method.name == "sendSecretMessageRead" && method.parameterCount == 2
        }?.let { return it }
        val classLoader = chatActivityClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val msgObjClass = resolveMessageObjectOrNull(classLoader)
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size == 2 &&
                ((msgObjClass != null && params[0] == msgObjClass) || params[0].name.endsWith("MessageObject")) &&
                params[1] == java.lang.Boolean.TYPE &&
                Runnable::class.java.isAssignableFrom(method.returnType)
        } ?: throw NoSuchMethodException("ChatActivity.sendSecretMessageRead(...) not found")
    }

    fun resolveSendSecretMediaDeleteMethod(chatActivityClass: Class<*>): Method? {
        cachedSendSecretMediaDeleteMethod?.let { return it }
        findDeclaredMethodByPredicate(chatActivityClass) { method ->
            method.name == "sendSecretMediaDelete"
        }?.let { return it }
        val classLoader = chatActivityClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val msgObjClass = resolveMessageObjectOrNull(classLoader)
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            Runnable::class.java.isAssignableFrom(method.returnType) && (
                (params.size == 1 && ((msgObjClass != null && params[0] == msgObjClass) || params[0].name.endsWith("MessageObject"))) ||
                    (
                        params.size == 2 && params[0] == chatActivityClass &&
                            ((msgObjClass != null && params[1] == msgObjClass) || params[1].name.endsWith("MessageObject"))
                    )
            )
        }
    }

    fun resolveAnimateToNextChatMethod(chatActivityClass: Class<*>): Method? =
        cachedAnimateToNextChatMethod
            ?: findMethodOrNull(chatActivityClass, "animateToNextChat")

    fun resolveForwardMessagesMethod(chatActivityClass: Class<*>): Method? =
        findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size == 6 &&
                ArrayList::class.java.isAssignableFrom(params[0]) &&
                params[1] == java.lang.Boolean.TYPE &&
                params[2] == java.lang.Boolean.TYPE &&
                params[3] == java.lang.Boolean.TYPE &&
                params[4] == java.lang.Integer.TYPE &&
                params[5] == java.lang.Long.TYPE
        }

    fun resolveContentPreviewGetInstance(contentPreviewViewerClass: Class<*>): Method =
        cachedContentPreviewGetInstanceMethod
            ?: findMethodOrNull(contentPreviewViewerClass, "getInstance")
            ?: findDeclaredMethodByPredicate(contentPreviewViewerClass) { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    contentPreviewViewerClass.isAssignableFrom(method.returnType)
            } ?: throw NoSuchMethodException("ContentPreviewViewer.getInstance() not found")

    // --- ProfileActivity Method Resolvers ---

    fun resolveProfileUpdateDataMethod(profileActivityClass: Class<*>): Method? =
        cachedProfileUpdateDataMethod
            ?: findMethodOrNull(profileActivityClass, "updateProfileData", java.lang.Boolean.TYPE)

    fun resolveProfileNeedLayoutMethod(profileActivityClass: Class<*>): Method? =
        cachedProfileNeedLayoutMethod
            ?: findMethodOrNull(profileActivityClass, "needLayout", java.lang.Boolean.TYPE)

    fun resolveProfileSetAvatarExpandProgressMethod(profileActivityClass: Class<*>): Method? =
        cachedProfileSetAvatarExpandProgressMethod
            ?: findMethodOrNull(profileActivityClass, "setAvatarExpandProgress", java.lang.Float.TYPE)

    // --- ChatActivity Field Resolvers ---

    fun findChatSelectedObjectField(chatActivityClass: Class<*>): Field =
        cachedSelectedObjectField
            ?: findFieldOrNull(chatActivityClass, "selectedObject")
            ?: run {
                val classLoader = chatActivityClass.classLoader ?: ClassLoader.getSystemClassLoader()
                val msgObjClass = resolveMessageObjectOrNull(classLoader)
                if (msgObjClass != null) {
                    chatActivityClass.declaredFields.firstOrNull { it.type == msgObjClass }?.also { it.isAccessible = true }
                } else {
                    null
                }
            }
            ?: chatActivityClass.declaredFields
                .singleOrNull { field -> field.type.name.endsWith("MessageObject") }
                ?.also { it.isAccessible = true }
            ?: throw NoSuchFieldException("ChatActivity.selectedObject not found")

    fun findChatSelectedObjectGroupField(chatActivityClass: Class<*>): Field =
        cachedSelectedObjectGroupField
            ?: findFieldOrNull(chatActivityClass, "selectedObjectGroup")
            ?: run {
                val classLoader = chatActivityClass.classLoader ?: ClassLoader.getSystemClassLoader()
                val grpClass = resolveGroupedMessages(classLoader)
                if (grpClass != null) {
                    chatActivityClass.declaredFields.firstOrNull { it.type == grpClass }?.also { it.isAccessible = true }
                } else {
                    null
                }
            }
            ?: chatActivityClass.declaredFields
                .singleOrNull { field ->
                    field.type.name.endsWith("MessageObject\$GroupedMessages")
                }?.also { it.isAccessible = true }
            ?: throw NoSuchFieldException("ChatActivity.selectedObjectGroup not found")

    fun findChatSelectedObjectToEditCaptionFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedSelectedObjectToEditCaptionField
            ?: findFieldOrNull(chatActivityClass, "selectedObjectToEditCaption")

    fun findChatScrimPopupWindowItemsFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedScrimPopupWindowItemsField
            ?: findFieldOrNull(chatActivityClass, "scrimPopupWindowItems")
            ?: chatActivityClass.declaredFields
                .singleOrNull { field ->
                    field.type.isArray &&
                        (
                            cachedActionBarMenuSubItemClass?.let { field.type.componentType == it }
                                ?: field.type.componentType.name
                                    .endsWith("ActionBarMenuSubItem")
                        )
                }?.also { it.isAccessible = true }

    fun findChatScrimPopupWindowFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedScrimPopupWindowField
            ?: findFieldOrNull(chatActivityClass, "scrimPopupWindow")
            ?: chatActivityClass.declaredFields
                .singleOrNull { field ->
                    cachedActionBarPopupWindowClass?.let { field.type == it }
                        ?: field.type.name.endsWith("ActionBarPopupWindow")
                }?.also { it.isAccessible = true }

    fun findChatUserInfoFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedChatUserInfoField
            ?: findFieldOrNull(chatActivityClass, "userInfo")
            ?: chatActivityClass.declaredFields.singleOrNull { it.type.name.endsWith("UserFull") }?.also { it.isAccessible = true }

    fun findChatForumTopicFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedChatForumTopicField
            ?: findFieldOrNull(chatActivityClass, "forumTopic")
            ?: chatActivityClass.declaredFields.singleOrNull { it.type.name.endsWith("TL_forumTopic") }?.also { it.isAccessible = true }

    fun findChatCurrentEncryptedChatFieldOrNull(chatActivityClass: Class<*>): Field? =
        findFieldOrNull(chatActivityClass, "currentEncryptedChat")
            ?: chatActivityClass.declaredFields.singleOrNull { it.type.name.endsWith("EncryptedChat") }?.also { it.isAccessible = true }

    fun findChatCurrentChatFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedChatCurrentChatField
            ?: findFieldOrNull(chatActivityClass, "currentChat")
            ?: chatActivityClass.declaredFields
                .singleOrNull {
                    it.type.name.endsWith("TLRPC\$Chat") || it.type.name.endsWith(".Chat")
                }?.also { it.isAccessible = true }

    fun resolveChatGetDialogIdMethodOrNull(chatActivityClass: Class<*>): Method? =
        cachedChatGetDialogIdMethod
            ?: findMethodOrNull(chatActivityClass, "getDialogId")

    fun getChatDialogId(chatActivity: Any): Long {
        val method = resolveChatGetDialogIdMethodOrNull(chatActivity.javaClass)
        if (method != null) {
            try {
                return method.invoke(chatActivity) as Long
            } catch (t: Throwable) {
                Log.w("MxGram", "Error invoking getDialogId method", t)
            }
        }
        val field = findFieldOrNull(chatActivity.javaClass, "dialog_id")
        if (field != null) {
            return field.get(chatActivity) as Long
        }
        throw NoSuchMethodException("getDialogId or field dialog_id not found on ${chatActivity.javaClass.name}")
    }

    fun resolveChatGetCurrentUserMethodOrNull(chatActivityClass: Class<*>): Method? =
        cachedChatGetCurrentUserMethod
            ?: findMethodOrNull(chatActivityClass, "getCurrentUser")

    fun resolveChatGetCurrentChatMethodOrNull(chatActivityClass: Class<*>): Method? =
        cachedChatGetCurrentChatMethod
            ?: findMethodOrNull(chatActivityClass, "getCurrentChat")

    fun resolveChatCanSendMessageMethodOrNull(chatActivityClass: Class<*>): Method? =
        cachedChatCanSendMessageMethod ?: findMethodOrNull(chatActivityClass, "canSendMessage")

    fun resolveChatSendMonoForumPeerIdMethodOrNull(chatActivityClass: Class<*>): Method? =
        cachedChatSendMonoForumPeerIdMethod ?: findMethodOrNull(chatActivityClass, "getSendMonoForumPeerId")

    fun resolveChatSendMessageSuggestionParamsMethodOrNull(
        chatActivityClass: Class<*>,
        paramsType: Class<*>,
    ): Method? =
        findMethodOrNull(chatActivityClass, "getSendMessageSuggestionParams")
            ?: findDeclaredMethodByPredicate(chatActivityClass) {
                it.parameterCount == 0 && it.returnType == paramsType && !Modifier.isStatic(it.modifiers)
            }

    fun findChatMessageSuggestionParamsFieldOrNull(
        chatActivityClass: Class<*>,
        paramsType: Class<*>,
    ): Field? =
        findFieldOrNull(chatActivityClass, "messageSuggestionParams")
            ?: chatActivityClass.declaredFields
                .singleOrNull {
                    it.type == paramsType && !Modifier.isStatic(it.modifiers)
                }?.also { it.isAccessible = true }

    fun findChatCurrentUserFieldOrNull(chatActivityClass: Class<*>): Field? =
        cachedChatCurrentUserField
            ?: findFieldOrNull(chatActivityClass, "currentUser")
            ?: chatActivityClass.declaredFields
                .singleOrNull {
                    (it.type.name.endsWith("TLRPC\$User") || it.type.name.endsWith(".User")) && !Modifier.isStatic(it.modifiers)
                }?.also { it.isAccessible = true }

    // --- ChatPullingDownDrawable Resolvers ---

    fun findPullingDownEmptyStubField(cls: Class<*>): Field =
        cachedPullingDownEmptyStubField
            ?: findFieldOrNull(cls, "emptyStub")
            ?: throw NoSuchFieldException("ChatPullingDownDrawable.emptyStub not found")

    fun findPullingDownNextChatField(cls: Class<*>): Field =
        cachedPullingDownNextChatField
            ?: findFieldOrNull(cls, "nextChat")
            ?: throw NoSuchFieldException("ChatPullingDownDrawable.nextChat not found")

    fun findPullingDownNextTopicField(cls: Class<*>): Field =
        cachedPullingDownNextTopicField
            ?: findFieldOrNull(cls, "nextTopic")
            ?: throw NoSuchFieldException("ChatPullingDownDrawable.nextTopic not found")

    fun findPullingDownNextDialogIdField(cls: Class<*>): Field =
        cachedPullingDownNextDialogIdField
            ?: findFieldOrNull(cls, "nextDialogId")
            ?: throw NoSuchFieldException("ChatPullingDownDrawable.nextDialogId not found")

    fun findPullingDownImageReceiverFieldOrNull(cls: Class<*>): Field? =
        cachedPullingDownImageReceiverField
            ?: findFieldOrNull(cls, "imageReceiver")

    fun resolvePullingDownUpdateMethods(cls: Class<*>): List<Method> {
        if (cachedPullingDownUpdateMethods.isNotEmpty()) {
            return cachedPullingDownUpdateMethods
        }
        val methods = mutableListOf<Method>()
        for (m in cls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) || m.returnType != java.lang.Void.TYPE) continue
            if (m.name == "updateDialog" || m.name == "updateTopic") {
                methods.add(m)
            }
        }
        return methods
    }

    // --- ContentPreviewViewer Resolvers ---

    fun findContentPreviewShowSheetRunnableField(cls: Class<*>): Field =
        cachedContentPreviewShowSheetRunnableField
            ?: findFieldOrNull(cls, "showSheetRunnable")
            ?: cls.declaredFields
                .singleOrNull {
                    Runnable::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
                }?.also { it.isAccessible = true }
            ?: throw NoSuchFieldException("ContentPreviewViewer.showSheetRunnable not found")

    fun findContentPreviewCurrentContentTypeField(cls: Class<*>): Field =
        cachedContentPreviewCurrentContentTypeField
            ?: findFieldOrNull(cls, "currentContentType")
            ?: throw NoSuchFieldException("ContentPreviewViewer.currentContentType not found")

    fun findContentPreviewCurrentDocumentField(cls: Class<*>): Field =
        cachedContentPreviewCurrentDocumentField
            ?: findFieldOrNull(cls, "currentDocument")
            ?: throw NoSuchFieldException("ContentPreviewViewer.currentDocument not found")

    fun findContentPreviewMenuVisibleField(cls: Class<*>): Field =
        cachedContentPreviewMenuVisibleField
            ?: findFieldOrNull(cls, "menuVisible")
            ?: throw NoSuchFieldException("ContentPreviewViewer.menuVisible not found")

    fun findContentPreviewMenuVisibleFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewMenuVisibleField
            ?: findFieldOrNull(cls, "menuVisible")

    fun findContentPreviewIsVisibleFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewIsVisibleField
            ?: findFieldOrNull(cls, "isVisible")

    fun findContentPreviewPopupWindowField(cls: Class<*>): Field =
        findContentPreviewPopupWindowFieldOrNull(cls)
            ?: throw NoSuchFieldException("ContentPreviewViewer.popupWindow not found")

    fun findContentPreviewPopupWindowFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewPopupWindowField
            ?: findFieldOrNull(cls, "popupWindow")
            ?: cls.declaredFields
                .singleOrNull {
                    android.widget.PopupWindow::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers)
                }?.also { it.isAccessible = true }

    fun findContentPreviewMoveYFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewMoveYField
            ?: findFieldOrNull(cls, "moveY")

    fun findContentPreviewKeyboardHeightFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewKeyboardHeightField
            ?: findFieldOrNull(cls, "keyboardHeight")

    fun resolveContentPreviewCloseWithMenuMethod(cls: Class<*>): Method? =
        cachedContentPreviewCloseWithMenuMethod
            ?: findMethodOrNull(cls, "closeWithMenu")

    fun resolveContentPreviewCloseMethod(cls: Class<*>): Method? =
        cachedContentPreviewCloseMethod
            ?: findMethodOrNull(cls, "close")

    fun resolveContentPreviewDismissPopupWindowMethod(cls: Class<*>): Method? =
        cachedContentPreviewDismissPopupWindowMethod
            ?: findMethodOrNull(cls, "dismissPopupWindow")

    fun findContentPreviewParentActivityField(cls: Class<*>): Field =
        findFieldOrNull(cls, "parentActivity")
            ?: cls.declaredFields.singleOrNull { it.type == Activity::class.java && !Modifier.isStatic(it.modifiers) }?.also {
                it.isAccessible =
                    true
            }
            ?: throw NoSuchFieldException("ContentPreviewViewer.parentActivity not found")

    fun findContentPreviewCurrentAccountField(cls: Class<*>): Field =
        cachedContentPreviewCurrentAccountField
            ?: findFieldOrNull(cls, "currentAccount")
            ?: throw NoSuchFieldException("ContentPreviewViewer.currentAccount not found")

    fun findContentPreviewContainerViewField(cls: Class<*>): Field =
        findFieldOrNull(cls, "containerView")
            // FrameLayoutDrawer overrides onDraw; windowView and reactionsLayoutContainer do not.
            ?: cls.declaredFields
                .singleOrNull {
                    FrameLayout::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) &&
                        findMethodOrNull(it.type, "onDraw", android.graphics.Canvas::class.java)?.declaringClass == it.type
                }?.also { it.isAccessible = true }
            ?: throw NoSuchFieldException("ContentPreviewViewer.containerView not found")

    fun findContentPreviewResourcesProviderFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewResourcesProviderField
            ?: findFieldOrNull(cls, "resourcesProvider")

    fun findContentPreviewIsPhotoEditorFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewIsPhotoEditorField
            ?: findFieldOrNull(cls, "isPhotoEditor")

    fun findContentPreviewCurrentPreviewCellFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewCurrentPreviewCellField
            ?: findFieldOrNull(cls, "currentPreviewCell")

    fun findContentPreviewUnlockPremiumViewFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewUnlockPremiumViewField
            ?: findFieldOrNull(cls, "unlockPremiumView")
            ?: cls.declaredFields
                .firstOrNull { field ->
                    android.widget.FrameLayout::class.java.isAssignableFrom(field.type) &&
                        field.type.declaredConstructors.any { c ->
                            c.parameterCount == 3 && c.parameterTypes[0] == android.content.Context::class.java &&
                                c.parameterTypes[1] == java.lang.Integer.TYPE
                        }
                }?.also { it.isAccessible = true }

    fun findUnlockPremiumButtonFieldOrNull(cls: Class<*>): Field? =
        findFieldOrNull(cls, "premiumButtonView")
            ?: cls.declaredFields
                .singleOrNull {
                    View::class.java.isAssignableFrom(it.type) && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
                }?.also { it.isAccessible = true }

    fun findContentPreviewStickerEmojiLayoutFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewStickerEmojiLayoutField
            ?: findFieldOrNull(cls, "stickerEmojiLayout")

    fun findContentPreviewDrawEffectFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewDrawEffectField
            ?: findFieldOrNull(cls, "drawEffect")

    fun findContentPreviewCloseOnDismissFieldOrNull(cls: Class<*>): Field? =
        cachedContentPreviewCloseOnDismissField
            ?: findFieldOrNull(cls, "closeOnDismiss")

    // --- RLottieDrawable Resolvers ---

    fun findRLottieMetaDataField(cls: Class<*>): Field? =
        cachedRLottieMetaDataField
            ?: findFieldOrNull(cls, "metaData")
            ?: cls.declaredFields
                .singleOrNull {
                    it.type == IntArray::class.java && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
                }?.also { it.isAccessible = true }

    fun resolveRLottiePrepareForGenerateCacheMethod(cls: Class<*>): Method? =
        cachedRLottiePrepareForGenerateCacheMethod
            ?: findMethodOrNull(cls, "prepareForGenerateCache")

    fun resolveRLottieReleaseForGenerateCacheMethod(cls: Class<*>): Method? =
        cachedRLottieReleaseForGenerateCacheMethod
            ?: findMethodOrNull(cls, "releaseForGenerateCache")

    fun resolveRLottieGetNextFrameMethod(cls: Class<*>): Method? =
        cachedRLottieGetNextFrameMethod
            ?: findMethodOrNull(cls, "getNextFrame", android.graphics.Bitmap::class.java)

    fun resolveRLottieRecycleMethod(cls: Class<*>): Method? =
        cachedRLottieRecycleMethod ?: findMethodOrNull(cls, "recycle", java.lang.Boolean.TYPE)

    // --- ProfileActivity Field Resolvers ---

    fun findProfileOnlineTextViewFieldOrNull(profileActivity: Any): Field? =
        cachedProfileOnlineTextViewField
            ?: findFieldOrNull(profileActivity.javaClass, "onlineTextView")
            ?: findProfileViewArrayField(profileActivity, 4)

    fun findProfileOnlineTextViewField(profileActivity: Any): Field =
        findProfileOnlineTextViewFieldOrNull(profileActivity)
            ?: throw NoSuchFieldException("ProfileActivity.onlineTextView not found")

    fun findProfileNameTextViewFieldOrNull(profileActivity: Any): Field? =
        cachedProfileNameTextViewField
            ?: findFieldOrNull(profileActivity.javaClass, "nameTextView")
            ?: findProfileViewArrayField(profileActivity, 2)

    fun resolveProfileGetDialogIdMethodOrNull(profileActivityClass: Class<*>): Method? =
        cachedProfileGetDialogIdMethod
            ?: findMethodOrNull(profileActivityClass, "getDialogId")

    fun resolveMessagesControllerGetUserMethod(messagesControllerClass: Class<*>): Method? =
        cachedMessagesControllerGetUserMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?: messagesControllerClass.declaredMethods
                .firstOrNull {
                    it.returnType.name.endsWith("TLRPC\$User") && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == java.lang.Long::class.java || it.parameterTypes[0] == java.lang.Long.TYPE)
                }?.also {
                    it.isAccessible = true
                    cachedMessagesControllerGetUserMethod = it
                }

    fun resolveMessagesControllerGetChatMethod(messagesControllerClass: Class<*>): Method? =
        cachedMessagesControllerGetChatMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?: messagesControllerClass.declaredMethods
                .firstOrNull {
                    it.returnType.name.endsWith("TLRPC\$Chat") && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == java.lang.Long::class.java || it.parameterTypes[0] == java.lang.Long.TYPE)
                }?.also {
                    it.isAccessible = true
                    cachedMessagesControllerGetChatMethod = it
                }

    fun findMessagesControllerBlockedPeersFieldOrNull(messagesControllerClass: Class<*>): Field? =
        cachedMessagesControllerBlockedPeersField
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?: findFieldOrNull(messagesControllerClass, "blockePeers")
            ?: messagesControllerClass.declaredFields
                .singleOrNull { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        findMethodOrNull(field.type, "indexOfKey", java.lang.Long.TYPE) != null
                }?.also { it.isAccessible = true }

    private fun findProfileViewArrayField(
        profileActivity: Any,
        length: Int,
    ): Field? =
        profileActivity.javaClass.declaredFields
            .filter {
                it.type.isArray && !Modifier.isStatic(it.modifiers) &&
                    it.type.componentType?.let { component -> View::class.java.isAssignableFrom(component) } == true
            }.onEach { it.isAccessible = true }
            .singleOrNull {
                (it.get(profileActivity) as? Array<*>)?.size == length
            }

    fun findProfileUserIdFieldOrNull(profileActivityClass: Class<*>): Field? = findFieldOrNull(profileActivityClass, "userId")

    fun findProfileChatIdFieldOrNull(profileActivityClass: Class<*>): Field? = findFieldOrNull(profileActivityClass, "chatId")

    fun findProfileDialogIdFieldOrNull(profileActivityClass: Class<*>): Field? = findFieldOrNull(profileActivityClass, "dialogId")

    fun findProfileChatInfoFieldOrNull(profileActivityClass: Class<*>): Field? =
        cachedProfileChatInfoField
            ?: findFieldOrNull(profileActivityClass, "chatInfo")
            ?: profileActivityClass.declaredFields.singleOrNull { it.type.name.endsWith("ChatFull") }?.also { it.isAccessible = true }

    fun findProfileUserInfoFieldOrNull(profileActivityClass: Class<*>): Field? =
        cachedProfileUserInfoField
            ?: findFieldOrNull(profileActivityClass, "userInfo")
            ?: profileActivityClass.declaredFields.singleOrNull { it.type.name.endsWith("UserFull") }?.also { it.isAccessible = true }

    fun isMessageObject(obj: Any?): Boolean {
        if (obj == null) return false
        val cls = obj.javaClass
        return cls.name.endsWith("MessageObject") || cls == cachedMessageObjectClass
    }

    fun findMessageOwnerField(messageObjectClass: Class<*>): Field {
        cachedMessageOwnerField
            ?.takeIf { it.declaringClass.isAssignableFrom(messageObjectClass) }
            ?.let { return it }
        val field =
            messageObjectClass.fields.firstOrNull { it.name == "messageOwner" }
                ?: messageObjectClass.fields.firstOrNull { it.type.name == "org.telegram.tgnet.TLRPC\$Message" }
                ?: messageObjectClass.declaredFields.firstOrNull { it.name == "messageOwner" }
                ?: messageObjectClass.declaredFields.firstOrNull { it.type.name == "org.telegram.tgnet.TLRPC\$Message" }
                ?: throw NoSuchFieldException("messageOwner field not found on ${messageObjectClass.name}")
        field.isAccessible = true
        cachedMessageOwnerField = field
        return field
    }

    fun findReplyMessageObjectField(messageObjectClass: Class<*>): Field {
        cachedReplyMessageObjectField
            ?.takeIf { it.declaringClass.isAssignableFrom(messageObjectClass) }
            ?.let { return it }
        val field =
            messageObjectClass.fields.firstOrNull { it.name == "replyMessageObject" }
                ?: messageObjectClass.fields.firstOrNull { it.type == messageObjectClass }
                ?: messageObjectClass.declaredFields.firstOrNull { it.name == "replyMessageObject" }
                ?: messageObjectClass.declaredFields.firstOrNull { it.type == messageObjectClass }
                ?: throw NoSuchFieldException("replyMessageObject field not found on ${messageObjectClass.name}")
        field.isAccessible = true
        cachedReplyMessageObjectField = field
        return field
    }

    fun getMessageOwner(messageObject: Any): Any? = findMessageOwnerField(messageObject.javaClass).get(messageObject)

    fun getReplyMessageObject(messageObject: Any): Any? = findReplyMessageObjectField(messageObject.javaClass).get(messageObject)

    fun getMessageId(messageObject: Any): Int {
        try {
            val method = messageObject.javaClass.getMethod("getId")
            return (method.invoke(messageObject) as Number).toInt()
        } catch (_: Throwable) {
        }
        val owner = getMessageOwner(messageObject)
        if (owner != null) {
            try {
                return findField(owner.javaClass, "id").getInt(owner)
            } catch (_: Throwable) {
            }
        }
        return 0
    }

    fun getMessageDialogId(messageObject: Any): Long {
        try {
            val method = messageObject.javaClass.getMethod("getDialogId")
            return (method.invoke(messageObject) as Number).toLong()
        } catch (_: Throwable) {
        }
        val owner = getMessageOwner(messageObject)
        if (owner != null) {
            try {
                return findField(owner.javaClass, "dialog_id").getLong(owner)
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    fun getMessageReplyMsgId(messageObject: Any): Int {
        try {
            val method = messageObject.javaClass.getMethod("getReplyMsgId")
            return (method.invoke(messageObject) as Number).toInt()
        } catch (_: Throwable) {
        }
        val owner = getMessageOwner(messageObject)
        if (owner != null) {
            try {
                val replyTo = findField(owner.javaClass, "reply_to").get(owner)
                if (replyTo != null) {
                    return findField(replyTo.javaClass, "reply_to_msg_id").getInt(replyTo)
                }
            } catch (_: Throwable) {
            }
        }
        return 0
    }

    fun getDocument(messageObject: Any): Any? {
        try {
            val method = messageObject.javaClass.getMethod("getDocument")
            return method.invoke(messageObject)
        } catch (_: Throwable) {
        }
        for (method in messageObject.javaClass.declaredMethods) {
            if (method.parameterCount == 0 && method.returnType.name == "org.telegram.tgnet.TLRPC\$Document") {
                method.isAccessible = true
                return method.invoke(messageObject)
            }
        }
        val owner = getMessageOwner(messageObject)
        if (owner != null) {
            try {
                val media = findField(owner.javaClass, "media").get(owner)
                if (media != null) {
                    return findField(media.javaClass, "document").get(media)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun getGroupedMessagesList(groupedMessages: Any): List<*>? {
        val field =
            groupedMessages.javaClass.fields.firstOrNull { it.name == "messages" }
                ?: groupedMessages.javaClass.fields.firstOrNull { java.util.ArrayList::class.java.isAssignableFrom(it.type) }
                ?: groupedMessages.javaClass.declaredFields.firstOrNull { it.name == "messages" }
                ?: groupedMessages.javaClass.declaredFields.firstOrNull { java.util.ArrayList::class.java.isAssignableFrom(it.type) }
        field?.isAccessible = true
        return field?.get(groupedMessages) as? List<*>
    }

    fun resolveGetForwardedNameMethod(messageObjectClass: Class<*>): Method {
        cachedGetForwardedNameMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messageObjectClass) }
            ?.let { return it }
        findMethodOrNull(messageObjectClass, "getForwardedName")?.let {
            cachedGetForwardedNameMethod = it
            return it
        }
        val method =
            messageObjectClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == String::class.java
            } ?: throw NoSuchMethodException("getForwardedName method not found on ${messageObjectClass.name}")
        method.isAccessible = true
        cachedGetForwardedNameMethod = method
        return method
    }

    fun resolveNoForwardsMethods(messagesControllerClass: Class<*>): List<Method> {
        val results = ArrayList<Method>()
        val classLoader = messagesControllerClass.classLoader
        val chatClass = Class.forName("org.telegram.tgnet.TLRPC\$Chat", false, classLoader)
        val userFullClass = Class.forName("org.telegram.tgnet.TLRPC\$UserFull", false, classLoader)

        // isChatNoForwards(TLRPC.Chat): boolean
        val chatMethod =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "isChatNoForwards" && m.parameterCount == 1
            } ?: messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && m.parameterTypes[0] == chatClass && m.returnType == java.lang.Boolean.TYPE
            }
        chatMethod?.let { results.add(it) }

        // isUserNoForwards(TLRPC.UserFull): boolean
        val userMethod =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "isUserNoForwards" && m.parameterCount == 1
            } ?: messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && m.parameterTypes[0] == userFullClass && m.returnType == java.lang.Boolean.TYPE
            }
        userMethod?.let { results.add(it) }

        // isPeerNoForwards / methods taking long returning boolean
        if (cachedPeerNoForwardsMethods.isNotEmpty()) {
            for (m in cachedPeerNoForwardsMethods) {
                if (!results.contains(m)) {
                    results.add(m)
                }
            }
        } else {
            messagesControllerClass.declaredMethods
                .firstOrNull { m ->
                    m.name == "isPeerNoForwards" && m.parameterCount == 1
                }?.let { results.add(it) }
        }

        return results
    }

    fun resolveCreateDeleteShowOnceTaskMethod(messagesControllerClass: Class<*>): Method {
        cachedCreateDeleteShowOnceTaskMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?.let { return it }
        val method =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "createDeleteShowOnceTask" && m.parameterCount == 2
            } ?: messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.returnType == java.lang.Long.TYPE && m.parameterCount == 2 &&
                    m.parameterTypes[0] == java.lang.Integer.TYPE && m.parameterTypes[1] == java.lang.Long.TYPE
            } ?: throw NoSuchMethodException("createDeleteShowOnceTask method not found on ${messagesControllerClass.name}")
        method.isAccessible = true
        cachedCreateDeleteShowOnceTaskMethod = method
        return method
    }

    fun resolveDoDeleteShowOnceTaskMethod(messagesControllerClass: Class<*>): Method {
        cachedDoDeleteShowOnceTaskMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?.let { return it }
        val method =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "doDeleteShowOnceTask" && m.parameterCount == 3
            } ?: messagesControllerClass.declaredMethods
                .filter { m ->
                    m.parameterCount == 3 && m.returnType == java.lang.Void.TYPE &&
                        m.parameterTypes[0] == java.lang.Long.TYPE &&
                        m.parameterTypes[1] == java.lang.Integer.TYPE &&
                        m.parameterTypes[2] == java.lang.Long.TYPE
                }.firstOrNull { it != cachedMarkMessageAsReadMethod }
                ?: throw NoSuchMethodException("doDeleteShowOnceTask method not found on ${messagesControllerClass.name}")
        method.isAccessible = true
        cachedDoDeleteShowOnceTaskMethod = method
        return method
    }

    fun resolveMarkMessageAsRead2Method(messagesControllerClass: Class<*>): Method {
        cachedMarkMessageAsRead2Method
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?.let { return it }
        val method =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "markMessageAsRead2" && m.parameterCount == 6
            } ?: messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 6 &&
                    m.parameterTypes[0] == java.lang.Long.TYPE &&
                    m.parameterTypes[1] == java.lang.Integer.TYPE &&
                    m.parameterTypes[3] == java.lang.Integer.TYPE &&
                    m.parameterTypes[4] == java.lang.Long.TYPE &&
                    m.parameterTypes[5] == java.lang.Boolean.TYPE
            } ?: throw NoSuchMethodException("markMessageAsRead2 method not found on ${messagesControllerClass.name}")
        method.isAccessible = true
        cachedMarkMessageAsRead2Method = method
        return method
    }

    fun resolveMarkMessageAsReadMethod(messagesControllerClass: Class<*>): Method {
        cachedMarkMessageAsReadMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(messagesControllerClass) }
            ?.let { return it }
        val method =
            messagesControllerClass.declaredMethods.firstOrNull { m ->
                m.name == "markMessageAsRead" && m.parameterCount == 3
            } ?: messagesControllerClass.declaredMethods
                .filter { m ->
                    m.parameterCount == 3 && m.returnType == java.lang.Void.TYPE &&
                        m.parameterTypes[0] == java.lang.Long.TYPE &&
                        m.parameterTypes[1] == java.lang.Integer.TYPE &&
                        m.parameterTypes[2] == java.lang.Long.TYPE
                }.firstOrNull { it != cachedDoDeleteShowOnceTaskMethod }
                ?: throw NoSuchMethodException("markMessageAsRead method not found on ${messagesControllerClass.name}")
        method.isAccessible = true
        cachedMarkMessageAsReadMethod = method
        return method
    }

    fun resolveFileLoaderInstance(
        fileLoaderClass: Class<*>,
        account: Int,
    ): Any? {
        val getInstance =
            fileLoaderClass.methods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterCount == 1 &&
                    m.parameterTypes[0] == java.lang.Integer.TYPE &&
                    fileLoaderClass.isAssignableFrom(m.returnType)
            }
        return getInstance?.invoke(null, account)
    }

    fun resolveGetPathToAttachMethod(
        fileLoaderClass: Class<*>,
        classLoader: ClassLoader,
    ): Method? {
        cachedGetPathToAttachMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(fileLoaderClass) }
            ?.let { return it }
        val tlObjectClass = Class.forName("org.telegram.tgnet.TLObject", false, classLoader)
        val method =
            fileLoaderClass.methods.firstOrNull { m ->
                File::class.java.isAssignableFrom(m.returnType) &&
                    m.parameterCount == 2 &&
                    tlObjectClass.isAssignableFrom(m.parameterTypes[0]) &&
                    m.parameterTypes[1] == java.lang.Boolean.TYPE
            }
        cachedGetPathToAttachMethod = method
        return method
    }

    fun resolveGetPathToMessageMethod(
        fileLoaderClass: Class<*>,
        classLoader: ClassLoader,
    ): Method? {
        cachedGetPathToMessageMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(fileLoaderClass) }
            ?.let { return it }
        val tlrpcMessageClass = Class.forName("org.telegram.tgnet.TLRPC\$Message", false, classLoader)
        val method =
            fileLoaderClass.methods.firstOrNull { m ->
                File::class.java.isAssignableFrom(m.returnType) &&
                    m.parameterCount == 1 &&
                    tlrpcMessageClass.isAssignableFrom(m.parameterTypes[0])
            }
        cachedGetPathToMessageMethod = method
        return method
    }
}
