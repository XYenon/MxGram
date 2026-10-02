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
    private var cachedChatPullingDownDrawableClass: Class<*>? = null
    private var cachedChatGreetingsViewClass: Class<*>? = null
    private var cachedContentPreviewViewerClass: Class<*>? = null
    private var cachedRLottieDrawableClass: Class<*>? = null
    private var cachedBulletinFactoryClass: Class<*>? = null
    private var cachedRecyclerListViewClass: Class<*>? = null
    private var cachedActionBarPopupWindowClass: Class<*>? = null
    private var cachedActionBarMenuSubItemClass: Class<*>? = null

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

    // Cached ProfileActivity fields resolved by DexKit
    private var cachedProfileChatInfoField: Field? = null
    private var cachedProfileUserInfoField: Field? = null

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

        // 2. ChatMessageCell: in org.telegram.ui.Cells, contains string "TodoCompleted"
        resolveFeature("ChatMessageCell", log) {
            bridge
                .findClass {
                    searchPackages("org.telegram.ui.Cells")
                    matcher {
                        usingStrings("TodoCompleted")
                    }
                }.singleOrNull()
                ?.let { data ->
                    cachedChatMessageCellClass = data.getInstance(classLoader)
                    log("DexKit resolved ChatMessageCell -> ${data.name}")
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
                cachedPullingDownEmptyStubField =
                    pullingDownData.methods
                        .singleOrNull {
                            it.paramCount == 0 && it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers)
                        }?.usedFields(owner, "boolean", FieldUsingType.Read)
                        ?.resolveField(classLoader, "ChatPullingDownDrawable.emptyStub", log)
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
                val close =
                    document?.writers.orEmpty().singleOrNull {
                        it.declaredClassName == owner && it.paramCount == 0 && it.returnTypeName == "void" &&
                            it.invokes.any { call -> call.name == "cancelRunOnUIThread" }
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
                            .intersect(open?.usedFields(owner, "boolean", FieldUsingType.Write).orEmpty().toSet()) - visible.toSet()
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
                val premiumDescriptionId =
                    getStaticIntFieldValue(
                        Class.forName("org.telegram.messenger.R\$string", false, classLoader),
                        "UnlockPremiumStickersDescription",
                        0,
                    )
                if (premiumDescriptionId != 0) {
                    val premiumType =
                        bridge
                            .findClass {
                                matcher {
                                    superClass("android.widget.FrameLayout")
                                    addMethod {
                                        addUsingField {
                                            declaredClass("org.telegram.messenger.R\$string")
                                            name("UnlockPremiumStickersDescription")
                                        }
                                    }
                                }
                            }.singleOrNull()
                            ?.name ?: bridge
                            .findClass {
                                matcher {
                                    superClass("android.widget.FrameLayout")
                                    addMethod { usingNumbers(premiumDescriptionId) }
                                }
                            }.singleOrNull()
                            ?.name
                    cachedContentPreviewUnlockPremiumViewField =
                        contentPreviewData.fields
                            .filter { it.typeName == premiumType }
                            .resolveField(classLoader, "ContentPreviewViewer.unlockPremiumView", log)
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
                        .singleOrNull {
                            it.paramCount == 0 && it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers) &&
                                it.usedFields(chatActivityData.name, "org.telegram.tgnet.TLRPC\$EncryptedChat", FieldUsingType.Read).size ==
                                1 &&
                                it.invokes.isNotEmpty() && it.invokes.all { call -> call.name == "getVisibility" }
                        }?.getMethodInstance(classLoader)
                cachedChatSendMonoForumPeerIdMethod =
                    methods
                        .singleOrNull {
                            it.paramCount == 0 && it.returnTypeName == "long" && !Modifier.isStatic(it.modifiers) &&
                                it.invokes.any { call ->
                                    call.declaredClassName == "org.telegram.messenger.ChatObject" && call.name == "canManageMonoForum"
                                } &&
                                it.invokes.any { call ->
                                    call.declaredClassName == "org.telegram.messenger.DialogObject" && call.name == "getPeerDialogId"
                                }
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
                        // The menu-delete countdown reads selectedObject; the edit-caption
                        // object and other message state also occur in processSelectedOption.
                        cachedSelectedObjectField =
                            methodData
                                .usedFields(chatActivityData.name, "org.telegram.messenger.MessageObject", FieldUsingType.Read)
                                .filter { field ->
                                    field.readers.any { reader ->
                                        reader.name == "run" && "Days" in reader.usingStrings &&
                                            reader.invokes.any {
                                                it.declaredClassName == "org.telegram.messenger.AndroidUtilities" &&
                                                    it.name == "formatDuration"
                                            }
                                    }
                                }.resolveField(classLoader, "ChatActivity.selectedObject", log)
                        cachedSelectedObjectGroupField =
                            methodData
                                .usedFields(
                                    chatActivityData.name,
                                    "org.telegram.messenger.MessageObject\$GroupedMessages",
                                    FieldUsingType.Read,
                                ).resolveField(classLoader, "ChatActivity.selectedObjectGroup", log)
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
                }
                bridge
                    .findMethod {
                        searchInClass(listOf(chatActivityData))
                        matcher {
                            addInvoke {
                                declaredClass("org.telegram.messenger.MessagesController")
                                name("createDeleteShowOnceTask")
                            }
                        }
                    }.singleOrNull()
                    ?.let { methodData ->
                        cachedSendSecretMediaDeleteMethod = methodData.getMethodInstance(classLoader)
                        log("DexKit resolved ChatActivity.sendSecretMediaDelete -> ${methodData.name}")
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
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size in 4..5 &&
                params[0].name.endsWith("MessageObject") &&
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
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size == 2 &&
                params[0].name.endsWith("MessageObject") &&
                params[1] == java.lang.Boolean.TYPE &&
                Runnable::class.java.isAssignableFrom(method.returnType)
        } ?: throw NoSuchMethodException("ChatActivity.sendSecretMessageRead(...) not found")
    }

    fun resolveSendSecretMediaDeleteMethod(chatActivityClass: Class<*>): Method? {
        cachedSendSecretMediaDeleteMethod?.let { return it }
        findDeclaredMethodByPredicate(chatActivityClass) { method ->
            method.name == "sendSecretMediaDelete" && method.parameterCount == 1
        }?.let { return it }
        return findDeclaredMethodByPredicate(chatActivityClass) { method ->
            val params = method.parameterTypes
            params.size == 1 &&
                params[0].name.endsWith("MessageObject") &&
                Runnable::class.java.isAssignableFrom(method.returnType)
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
            ?: throw NoSuchFieldException("ChatActivity.selectedObject not found")

    fun findChatSelectedObjectGroupField(chatActivityClass: Class<*>): Field =
        cachedSelectedObjectGroupField
            ?: findFieldOrNull(chatActivityClass, "selectedObjectGroup")
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

    fun findProfileOnlineTextViewField(profileActivity: Any): Field =
        findFieldOrNull(profileActivity.javaClass, "onlineTextView")
            ?: findProfileViewArrayField(profileActivity, 4)
            ?: throw NoSuchFieldException("ProfileActivity.onlineTextView not found")

    fun findProfileNameTextViewFieldOrNull(profileActivity: Any): Field? =
        findFieldOrNull(profileActivity.javaClass, "nameTextView")
            ?: findProfileViewArrayField(profileActivity, 2)

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
}
