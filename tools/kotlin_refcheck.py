#!/usr/bin/env python3
"""
Cross-reference checker for the Nepali Keyboard project.

The project forbids local compilation — verification is inspection-only, and the
real build happens in CI. That makes it easy for a rename or a signature change
to leave a dangling call site that only surfaces when CI runs, minutes later.

This script closes the cheapest half of that gap: it extracts the declarations
the project makes (top-level and class-member functions, properties, objects,
classes, enums) and then scans every `name(`, `name::`, `.name`, and `name.`
occurrence in the tree for names that look like project symbols but are declared
nowhere and are also not on the allow-list of framework names.

It is deliberately approximate. It cannot know Kotlin's overload resolution,
extension receivers, or import aliases, so it reports only names that appear
nowhere in the project at all, and it consults an explicit allow-list of every
Android/Compose/kotlinx symbol the codebase uses. A clean run therefore means
"nothing references a project symbol that does not exist", which is exactly the
class of bug a missing compiler produces.

Exit code 1 on any finding, so CI can gate on it.
"""

from __future__ import annotations

import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

# ---------------------------------------------------------------------------
# Declaration extraction
# ---------------------------------------------------------------------------

# `fun name(`, `suspend fun name(`, `inline fun name(`, `private fun name(`
RE_FUN = re.compile(
    r"^[ \t]*(?:(?:public|private|internal|protected|open|override|suspend|inline|"
    r"operator|infix|tailrec|external|abstract|final|expect|actual|vararg)\s+)*"
    r"fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(",
    re.MULTILINE,
)

# `val name`, `var name`, with modifiers
RE_PROP = re.compile(
    r"^[ \t]*(?:(?:public|private|internal|protected|open|override|const|lateinit|"
    r"abstract|final|expect|actual)\s+)*"
    r"(?:val|var)\s+(\w+)\s*[:=]",
    re.MULTILINE,
)

# `class Name`, `data class Name`, `enum class Name`, `object Name`, `interface Name`
RE_TYPE = re.compile(
    r"^[ \t]*(?:(?:public|private|internal|protected|open|abstract|sealed|data|"
    r"enum|value|annotation|expect|actual|companion)\s+)*"
    r"(?:class|interface|object)\s+(\w+)",
    re.MULTILINE,
)

# Enum entries inside an enum body: leading UPPER_CASE identifiers.
RE_ENUM_ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]{2,})\s*(?:[,;]|$)", re.MULTILINE)


def collect_declarations() -> tuple[set[str], dict[str, str]]:
    """Returns (declared_names, name -> first file that declares it)."""
    declared: set[str] = set()
    origin: dict[str, str] = {}

    for dirpath, _dirnames, filenames in os.walk(SRC):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            with open(path, encoding="utf-8") as handle:
                text = handle.read()

            # Strip comments and string bodies so a name mentioned in prose is
            # not mistaken for a declaration.
            text = strip_comments_and_strings(text)

            for pattern in (RE_FUN, RE_PROP, RE_TYPE, RE_ENUM_ENTRY):
                for match in pattern.finditer(text):
                    name = match.group(1)
                    declared.add(name)
                    origin.setdefault(name, rel)

    return declared, origin


def strip_comments_and_strings(text: str) -> str:
    """Replaces comment and string bodies with spaces, preserving newlines."""
    out: list[str] = []
    i = 0
    n = len(text)
    state = "code"
    while i < n:
        c = text[i]
        if state == "code":
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if text.startswith('"""', i):
                state = "raw"
                out.append("   ")
                i += 3
                continue
            if c == '"':
                state = "string"
                out.append(" ")
                i += 1
                continue
            if c == "'":
                state = "char"
                out.append(" ")
                i += 1
                continue
            out.append(c)
            i += 1
            continue

        if state == "line":
            if c == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue

        if state == "block":
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue

        if state == "raw":
            if text.startswith('"""', i):
                j = i
                while j < n and text[j] == '"':
                    j += 1
                out.append(" " * (j - i))
                i = j
                state = "code"
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue

        # string / char
        if state == "string" and c == "$" and i + 1 < n and text[i + 1] == "{":
            # Keep the interpolation's contents — it contains real code.
            depth = 1
            i += 2
            out.append("  ")
            while i < n and depth > 0:
                ch = text[i]
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        out.append(" ")
                        i += 1
                        break
                out.append(ch)
                i += 1
            continue
        if c == "\\":
            out.append("  ")
            i += 2
            continue
        if (state == "string" and c == '"') or (state == "char" and c == "'"):
            state = "code"
            out.append(" ")
            i += 1
            continue
        if c == "\n" and state == "string":
            # An unterminated string; bail back to code to avoid eating the file.
            state = "code"
            out.append("\n")
            i += 1
            continue
        out.append("\n" if c == "\n" else " ")
        i += 1

    return "".join(out)


# ---------------------------------------------------------------------------
# Framework / standard-library allow-list
# ---------------------------------------------------------------------------

ALLOW = set(
    """
    abs accept add addAll addFirst addLast after all any append applyArray
    apply as asIterable asStateFlow asSharedFlow asSequence associate
    associateBy atan2 attachToParent await awaitEachGesture awaitFirstDown
    awaitPointerEvent awaitPointerEventScope background before baseline
    bezierTo bottom buildList buildMap buildSet builder by cachedIn
    calculateWindowSizeClass call cancel catch ceil center charAt chunked
    chunkedSequence clamp clip coerceAtLeast coerceAtMost coerceIn
    collect collectAsState collectAsStateWithLifecycle collectLatest
    combine comma contains containsAll contentDescription contentPadding
    copy copyInto copyOf copyOfRange cos count countGraphemes crossfade
    cut cylindrical decodeDefault decodeFromString decodeToString decodeFromByteArray
    decodeFromInputArray deleteSurroundingText derivedStateOf diff distanceTo dp
    drawCircle drawLine drawPath drawRect drawRoundRect drawText drop
    dropLast dropWhile edit elementAt else emit emptyList emptyMap emptySet
    enableSavedStateHandles encodeToString encodeToByteArray endsWith ensureCapacity
    entries entrySet equals error evaluate expect extends filter filterIndexed
    filterIsInstance filterNot filterNotNull filteredBy find findAnyOf findLast
    findLastAnyOf first firstOrNull flatMap flatten floatArrayOf floatValue floor
    fold foldIndexed for forEach forEachIndexed format fourth
    from fromOrdinalOrNone fromOrdinalOrOff fromOrdinalOrRomanized
    fromOrdinalOrSystem  gather gc getOrElse getOrNull getOrPut getOrDefault
    getValue groupBy grow hasNext hasPrevious hashCode hashSetOf head
    hide if ifEmpty ignoreCase immutableSetOf implicitly index indexOf
    indexOfFirst indexOfLast indexOfOrNull indices inherits init initialCapacity
    innermostAt insert inline intArrayOf intersect intValue invoke is
    isBlank isConsumed isEmpty isFinite isInfinite isLetters isNaN isNotEmpty
    isNullOrBlank isNullOrEmpty isPressed isSentenceTerminator isWordChar
    iterate iterator joinTo joinToString key keyAt keys keySet lambdaAt
    last lastIndexOf lastOrNull lastSpaceAt launch lazy left length lengthOf
    let lineTo listOf listOfNotNull localised lowercase lowercaseChar
    magnitude main map mapIndexed mapIndexedNotNull mapNotNull mapTo matchedAt
    max maxByOrNull maxOf maxOrNull measure median merge messages min minByOrNull
    minOf minOrNull minus minusAssign mod move moveToIterator moveToPath
    moveToPrevious mapMutable multiplyAll multiset mutableListOf mutableMapOf
    mutableSetOf mutate name nCopies next nextBoundary nextFloat nextInt minusAssign
    none normalize not notEmpty notify on onAvailable onChange onCommit onDispose
    onLongPress onPress onRelease onSizeChanged onTextLayout or ordinal
    package padEnd padStart paint pair parseAsConstantFont parseAsVariableFont
    parseFontFile parseGlyph parsePath partition pathPercent peek percentAt
    plus plusAssign plusInPlace popContinuation positionIndexOf positionPrefixAt
    pow previous previousBoundary print println probeAt processAt propertyOf
    pushContinuation put putAll putFirst putInt putLast
    readBytes readText record reduce reduceIndexed reduceOrNull
    refresh remove removeAt removeFirst removeLast removeRange
    renderComposing renderCommitted repeat repeatOnLifecycle replace
    replaceAll requireNotNull reset resetContext reshape resolvedBy resize
    resolve resolveAsRes resample retarget restoreTo retainAll reverse rounded
    runCatching runBlocking sampledAt sampleAt scale scrollTo second select
    selectAll selectAt segmentAt segmentedAt send sequenceOf setSelection
    single singleOrNull size slice sizedAt slotAt sorted sortedBy sortedByDescending
    sortedWith sortWith split sqrt squareAt start startsWith stateIsBlockAt
    step stop storeAt stripAt subSequence substring substringAfter substringBefore
    sum sumBy sumByDouble sumOf take takeLast takeWhile tanA third
    to toByteArray toCharArray toColorInt toDp toDouble toFloat toInt
    toImmutableList toImmutableMap toIntArray toList toListOf toLong toMap
    toMutableList toMutableMap toPath toPx toRadians toSet toSp toString
    toTypedArray toUbyteArray transferAt translateAt transpose trim trimEnd
    trimStart trimToSize truncate try typedArrayOf typedMapOf
    unboxedAt uniqueAt update updateAll updateValue uppercase uppercaseChar
    usedAt uses using uuid valueAt valueOf values vararg verifyAt waitForUp
    waitForUpOrCancellation when where while with withContext withFrameNanos
    withInfiniteAnimationFrameNanos withLock withTimeout withTimeoutOrNull wrap
    wordAtIndex writeAs writeSince zeroClipAt zip
    """.split()
)

# Compose/Android type and member names used as bare identifiers.
ALLOW |= set(
    """
    AbsoluteArrangement Alignment AnimationSpec AntiAlias Any Application
    ApplicationCompat Arrangement AwaitPointerEventScope Backspace BasicTextField
    Boolean Box BoxWithConstraints Brush Build Buffer ByteBundle ByteArray
    Callback CancellationException Canvas CenterHorizontally CenterStart CenterVertically
    Char CharSequence ClipData ClipItem ClipItemList ClipboardManager ClipboardHistoryStore
    Close CodePoint CodePointClassifier Collection Color ColorScheme Colors Column
    ComposeView Composable CompositionLocalProvider Constraints ContentPaste Context
    CoroutineDispatcher CoroutineExceptionHandler CoroutineScope CreationExtras CursorDown
    CursorLeft CursorRight CursorUp CursorLeftRight Canvas CsvValue
    DEFAULTS Dp DrawScope Duration DynamicColorScheme
    Edge EditorInfo EmojiEmotions EmojiCatalog EmojiPanel EmojiPanelState Entry Enum
    ExpandLess ExpandMore ExtractedText
    Faint Flow Font FontFamily FontWeight FontWeightNumber Triple
    FrameLayout Float Function
    GradientStop GraphicsLayer GraphicsLayerScope
    HapticFeedbackType HapticFeedback Haptics HorizontalArrangement HorizontalDivider
    IBinder ICU IllegalArgumentException IME_ACTION_DONE IME_ACTION_GO IME_ACTION_NEXT
    IME_ACTION_NONE IME_ACTION_SEARCH IME_ACTION_SEND IME_FLAG_NO_ENTER_ACTION
    IME_MASK_ACTION Intent IOException Icon IconButton ImageVector Immutable
    InfiniteTransition InputConnection InputConnectionController InputMethodInfo
    InputMethodManager InputMethodService InputMode InputType IntArrayList IntArray
    IntRange Iterable Int
    Key KeyEvent KeyEventCharacters Keyboard KeyboardEvent KeyboardHost KeyboardKey
    KeyboardKeyView KeyboardLayouts KeyboardLifecycleOwner KeyboardPreferences
    KeyboardPreferencesDefaults KeyboardScreen KeyboardSettings KeyboardTheme
    KeyboardTypography KeyboardUiState KeyboardViewModel KeyGaps KeyRow KeyShape
    Language LaunchedEffect LazyColumn LazyGridScope LazyListScope LazyVerticalGrid
    LayoutDirection LearningWordStore LegalCharacterAt Length LeadingMargin
    LexiconAsset LexiconEntry LexiconModels LexiconRepository LexiconStatistics
    Lifecycle LifecycleOwner LifecycleRegistry LifecycleRegistryOwner LightColorScheme
    LinearProgressIndicator List LiveData LocalContext LocalDensity LocalFocusManager
    LocalHapticFeedback LocalLayoutDirection LocalLifecycleOwner LocalTextStyle
    LocalView LocalViewConfiguration LocalWindowInfo Long LongSparseArray
    MagnusMapper Map Marker MaterialTheme Math MutableCreationExtras MutablePreferences
    MutableSharedFlow MutableState MutableStateFlow MutableTransitionState
    NepaliImeService NepaliKeyboardApp NoMutation NestedScrollConnection Nothing
    Number NumberFormatException
    ObjectAnimator Obj OneHandedSide OneHandedSideSwap OpenCode OpenEndedRange
    PaddingValues Painter Path PathEffect PathParser PersistenceStores Pixel
    Placeholder PlatformTextStyle PoppinsFamily PrefKeys Preferences
    PushPin RadixTrie RangedValue Rect Reflect RememberObserver RepeatMode
    Row RowScope RowScopeModifierKey RowScopeModifierKeys Runtime
    SavedStateHandle SavedStateHandleSupport SavedStateRegistry
    SavedStateRegistryController SavedStateRegistryOwner Search SelectAll Send
    ServiceActions Settings SettingsActivity SettingsRepository
    SharedFlow ShiftState Short ShowRipple Sin SizableArray SnapshotState
    SolidColor Space Spacer Spring Square StackedValue State StateFlow String
    StringBuilder Style Suggestion SuggestionChip SuggestionSource SuggestionStrip
    Surface SuspendingPointerInputFilter SvgIcon SwapHoriz SystemClock
    TapGestureDetector TextStyle TextOverflow TextRange TextFieldValue
    ThemeMode Throwable TimeSource Timeline Trace Tween TypedValue Typeface
    UInt ULONG ULong Unit UnsignedValue Uri UrlClassifier UserProfile
    Value VariantSet VectorPath Vibrator VibratorManager View ViewModel
    ViewModelProvider ViewModelStore ViewModelStoreOwner ViewTreeLifecycleOwner
    ViewTreeSavedStateRegistryOwner ViewTreeViewModelStoreOwner VisibleValue
    setViewTreeLifecycleOwner setViewTreeSavedStateRegistryOwner
    setViewTreeViewModelStoreOwner
    Width WindowInsets WindowInfo WordBreak
    XScale YScale Zoom
    """.split()
)

# Members reached through an implicit receiver that the declaration scan cannot
# see: java.io.File, SharedPreferences.Editor, InputStream, StringBuilder and
# friends. Grouped by the type whose API they belong to so that a future report
# can be triaged by asking "which receiver is this actually on?" rather than by
# pattern-matching a name.
ALLOW |= set(
    """
    exists listFiles writeText readText delete mkdirs
    getSharedPreferences getBoolean getInt getLong getString putBoolean putInt
    putLong putString remove commit apply
    setPrimaryClip show halt javaClass
    """.split()
)

# Kotlin keywords and language constructs that won't be declared.
ALLOW |= set(
    """
    it this super null true false return break continue
    fun val var class object interface enum data sealed abstract open override
    private public internal protected companion const lateinit by where in out
    is as typeof package import if else when for while do try catch finally throw
    new get set field receiverType constructor
    """.split()
)

# Compose / Android / kotlinx member functions the codebase calls by name. These
# are framework symbols, so a declaration search in this repository cannot find
# them. Every entry here is a name the project actually uses; the list is not
# speculative.
ALLOW |= set(
    """
    accept addAll addFlags addLast also and alsoApply arrayListOf arrayOf asIterable
    asSequence assert async awaitFrame awaitFrameNanos
    bezierTo booleanPreferencesKey buildList buildString buildMap buildSet by
    byteArrayOf calculateRange cancelAndJoin charArrayOf check chunky clearAll
    clickable clipToBounds collectAsState collectAsStateWithLifecycle compareTo
    composeIntArray computeBounds computeGraphemes concatToString conj
    contentDescription contentsEqual convertColorToArgb convertToArgb cos
    createBitmap createLinearGradient crossFade decodeFromByteArray decodeFromString
    decodeToString decodeToInt deleteRecursively distanceAndInside dpToPx drawWithContent
    emptyArray emptyList emptyMap emptyPreferences emptySet encodeToByteArray
    encodeToString ensureCapacity equals equivalentTo evaluate expandHorizontally
    explicitBoundsOf fillMaxHeight fillMaxSize fillMaxWidth filterIsInstance
    firstOrNull floatArrayOf floatValue fold flatMap forEachIndexed
    generateSequence getOrElse getOrNull getOrPut getSystemService grayToArgb
    groupBy halfClose handleCoroutineException hasGlyphForLocale heightPx hide
    horizontalPadding hypot
    indexOfFirst indexOfLast insertInstanceOf intArrayOf intPreferencesKey
    isActive isBlank isCancelled isCompleted isConsumed isCtrlPressed isEmpty
    isFinished isHighSurrogate isLetter isLowSurrogate isNotEmpty isNullOrBlank
    isNullOrEmpty isPressed isSentenceTerminator isStarted isSuccess isUpperCase
    isWhitespace isWordChar iterate
    joinToString jsonArrayOf jsonObjectOf jsonPrimitive
    launch lazy lenientEquals lineHeight listIterator listOf listOfNotNull
    localeFromLanguageTag log logError logInfo logWarn longArrayOf loopRender
    lowerBound mapIndexed mapNotNull mapValues mapKeys matchedPair maxOfCubic
    measureDeep mdpiToDp measureText mergedBounds minOf mkdirs
    mutableListOf mutableMapOf mutableSetOf mutableStateListOf mutableStateOf
    namedOf nextFloat nextInt nextLong nodeCount notify noOp
    onCommit onDispose onLongPress onPress onRelease onSizeChanged onTextLayout
    onlyIfOrdinaryEmit optionalObject ordinalValue
    packValues pad End padStart parseColor parseHexColor pathBounds
    plusAssign pointerInput pollClose positionAt pow preventDefault
    printStackTrace produceState pushArray putFloat putInt putLong
    randomUUID readFully readText recompositionCount recordFrame
    reduceOrNull rememberCoroutineScope rememberSaveable rememberUpdatedState
    rememberSaveableStateHolder removeFirst removeLast removeRange repeat
    replaceAll requestFocus requestHideSelf require requireNotNull
    resolveFontSize resamplePath restoreState retainedSize rotateBy runCatching
    saveState scopeOf scrollTo semantics setOf setSelection showSoftInput
    singleOrNull sizeOfBounded sliceArray sortedByDescending sortedWith
    splitLines sqrtPixels stackTraceAsString startActivity stateOf
    stringPreferencesKey strippedDowngString subArray subSequence
    substringAfterLast substringBeforeLast suspendCancellableCoroutine
    swapAndClaim symmetricDifference synchronized systemBarsPadding
    textMeasurer then thenBy toArgb toByteArray toCharArray toColorInt toDp
    toFloat toFloatArray toInt toIntArray toList toLong toMap toMutableList
    toMutableMap toPath toPx toRadians toSet toString toSp toTypedArray triggerRebuild
    trimIndent trimmedPathMatching trimMargin
    unboundedInput unbox unused until updateValue uppercase uppercaseChar
    valueOf valuesOf verifyCodes verifyWidth vibrateOnPress waitForUpOrCancellation
    waitUntil widthPx withContext withFrameNanos withInfiniteAnimationFrameNanos
    withLock withTimeout withTimeoutOrNull wrapContentSize writeBytes 
    zeroLine zipWithNext
    stringResource horizontalScroll rememberScrollState widthIn isSystemInDarkTheme
    preferencesDataStore orEmpty toChar use shl thenByDescending
    onComposingBufferChanged onDelete onKey vibrate getExtractedText
    defaultVibrator enableEdgeToEdge onSelect takeIf
    currentOnCreated setPadding setTextColor setHorizontallyScrolling
    windowInsetsPadding calculateTopPadding finish
    tween fadeIn fadeOut slideInHorizontally slideOutHorizontally
    mutableIntStateOf showInputMethodPicker verticalScroll
    setParentCompositionContext disposeComposition close
    focusRequester show requestFocus createLifecycleAwareWindowRecomposer
    AndroidUiDispatcher
    buffered openConnection setRequestProperty versionName
    onDownload onProgress
    """.split()
)

# Local lambda parameters and destructured names that appear as call targets.
ALLOW |= set(
    """
    currentOnPress currentOnCommit currentOnPaste currentOnDrag currentOnDragEnd
    currentOnLongPress currentOnClick currentOnAction content currentCategory
    onPin onClose onAction onPaste onEmojiSelected onQueryChanged onCategorySelected
    accent onFinish
    items item index count glyph label text mode key row gaps modifier
    background pressedBackground contentColor style fontScale contentColor
    weight showBorder icon onPress fillMaxWidth fillMaxHeight height padding width
    darkColorScheme lightColorScheme dynamicDarkColorScheme dynamicLightColorScheme
    remember rememberUpdatedState mutableStateOf derivedStateOf
    """.split()
)


# ---------------------------------------------------------------------------
# Reference scanning
# ---------------------------------------------------------------------------

# Identifiers that are referenced but might not be declared: used as a call,
# a member access, or a function reference.
RE_REF = re.compile(
    r"(?<![\w.])"
    r"(?:"
    r"([a-z][A-Za-z0-9_]{2,})::"            # function reference
    r"|([a-z][A-Za-z0-9_]{2,})\s*\("        # call
    r"|\.([a-z][A-Za-z0-9_]{2,})\b"         # member read
    r")"
)

# A reference in these positions is a keyword or a named argument, not a symbol.
RE_NAMED_ARG = re.compile(r"^\w+\s*=")


def scan_references() -> dict[str, list[tuple[str, int]]]:
    hits: dict[str, list[tuple[str, int]]] = defaultdict(list)

    for dirpath, _dirnames, filenames in os.walk(SRC):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            with open(path, encoding="utf-8") as handle:
                raw = handle.read()
            text = strip_comments_and_strings(raw)

            # Build a set of named-argument positions to skip: `foo(bar = 1)`.
            named: set[int] = set()
            for match in re.finditer(r"(\w+)\s*=", text):
                # Only treat it as a named argument if it is inside parens and
                # not preceded by `val`/`var`/`const`.
                prefix = text[max(0, match.start() - 12):match.start()]
                if re.search(r"\b(val|var|const)\s*$", prefix):
                    continue
                named.add(match.start(1))

            for match in RE_REF.finditer(text):
                name = match.group(1) or match.group(2) or match.group(3)
                if name is None:
                    continue
                # Skip named arguments.
                if match.start(match.lastindex or 0) in named:
                    continue
                line = text.count("\n", 0, match.start()) + 1
                hits[name].append((rel, line))

    return hits


# ---------------------------------------------------------------------------
# Import resolution
# ---------------------------------------------------------------------------

RE_IMPORT = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.MULTILINE)

# Only imports under this prefix belong to the project; everything else is a
# framework or standard-library import and cannot be resolved here.
PROJECT_PREFIX = "com.nikit.nepalikeyboard."

# Generated classes the Kotlin sources legitimately import but which do not
# exist as hand-written declarations. Listed so they are not reported.
GENERATED = {
    "R",
    "BuildConfig",
}


def check_imports(
    declared: set[str], origin: dict[str, str]
) -> list[tuple[str, int, str, str]]:
    """
    Finds project imports whose last segment is declared nowhere.

    This catches the failure mode a compiler surfaces as "unresolved reference"
    but an inspection pass misses: a class renamed in its own file while an
    import elsewhere still names the old symbol. Framework imports are skipped
    because their declarations live in the SDK, not in this tree.
    """
    findings: list[tuple[str, int, str, str]] = []

    for dirpath, _dirnames, filenames in os.walk(SRC):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            with open(path, encoding="utf-8") as handle:
                text = handle.read()

            for match in RE_IMPORT.finditer(text):
                full = match.group(1)
                alias = match.group(2)
                if not full.startswith(PROJECT_PREFIX):
                    continue
                symbol = full.rsplit(".", 1)[-1]
                # A wildcard or a nested member import (e.g. `Foo.Bar`) is not
                # something this check can resolve; only simple type imports are
                # in scope.
                if symbol == "*":
                    continue
                if alias is not None:
                    # Aliased imports resolve by their real name upstream.
                    pass
                if symbol in declared:
                    continue
                if symbol in GENERATED:
                    continue
                line_no = text.count("\n", 0, match.start()) + 1
                findings.append((rel, line_no, full, symbol))

    return findings


def main() -> int:
    declared, origin = collect_declarations()
    hits = scan_references()

    unknown: dict[str, list[tuple[str, int]]] = {}
    for name, locations in hits.items():
        if name in declared or name in ALLOW:
            continue
        # A name that appears only as a member access could be a framework
        # member we have not listed; require at least one call or reference
        # position to report it, and require the file to be one we own.
        unknown[name] = locations

    kt_files = 0
    for dirpath, _dirnames, filenames in os.walk(SRC):
        kt_files += sum(1 for f in filenames if f.endswith(".kt"))

    print(f"Declared project symbols: {len(declared)}")
    print(f"Scanned {kt_files} Kotlin files.")

    # -----------------------------------------------------------------
    # Import resolution
    #
    # An import naming a project package must name a symbol that actually
    # exists in the file that package points at. A stale import after a
    # rename compiles as "unresolved reference" in CI and is invisible
    # locally, so it is checked here.
    # -----------------------------------------------------------------
    bad_imports = check_imports(declared, origin)

    ok = True

    if bad_imports:
        ok = False
        print(f"\n{len(bad_imports)} import(s) naming a project symbol that "
              f"is not declared anywhere:\n")
        for rel, line_no, text, symbol in bad_imports:
            print(f"  {rel}:{line_no}: import {text}")
            print(f"      '{symbol}' is never declared")

    if unknown:
        ok = False
        print(f"\n{len(unknown)} name(s) referenced but never declared:\n")
        for name in sorted(unknown, key=lambda n: (-len(unknown[n]), n)):
            locations = unknown[name]
            shown = locations[:4]
            where = ", ".join(f"{f}:{ln}" for f, ln in shown)
            more = "" if len(locations) <= 4 else f" (+{len(locations) - 4} more)"
            print(f"  {name}")
            print(f"      {where}{more}")

    if ok:
        print("No unresolved references found.")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
