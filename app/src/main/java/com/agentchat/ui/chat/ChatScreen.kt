package com.agentchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentchat.R
import com.agentchat.apps.InstalledApp
import com.agentchat.data.Message
import com.agentchat.data.MessageSender
import com.agentchat.data.MessageStatus
import com.agentchat.ui.theme.AccentLavender
import com.agentchat.ui.theme.AccentLime
import com.agentchat.ui.theme.AccentOrange
import com.agentchat.ui.theme.AccentPink
import com.agentchat.ui.voice.VoicePanel
import com.agentchat.voice.VoiceState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    messages: List<Message>,
    installedApps: List<InstalledApp>,
    serviceEnabled: Boolean,
    voiceState: VoiceState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showSetup by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val mention = remember(input) { detectMention(input) }

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartVoice() }

    if (showSetup) {
        com.agentchat.ui.settings.SetupSheet(
            serviceEnabled = serviceEnabled,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenAssistantSettings = onOpenAssistantSettings,
            onDismiss = { showSetup = false },
        )
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (mention != null) {
        AppPickerSheet(
            initialQuery = mention.query,
            apps = installedApps,
            loading = installedApps.isEmpty(),
            onSelect = { app -> input = applyMention(input, mention, app.mentionToken) },
            onDismiss = {
                // Collapse the mention by inserting a space so it no longer matches.
                val caret = input.selection.end
                input = TextFieldValue(
                    text = input.text.substring(0, caret) + " " + input.text.substring(caret),
                    selection = androidx.compose.ui.text.TextRange(caret + 1),
                )
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TappyHeader(onOpenSetup = { showSetup = true }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding(),
        ) {
            if (!serviceEnabled) {
                ServiceDisabledBanner(onEnable = { showSetup = true })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (messages.isEmpty()) {
                    item { EmptyState() }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    val next = messages.getOrNull(index + 1)
                    if (showTimestampBefore(previous, message)) {
                        TimestampDivider(message.timestamp)
                    }
                    MessageBubble(
                        message = message,
                        isLastInGroup = next?.sender != message.sender,
                        isLatest = index == messages.lastIndex,
                        onStop = onStop,
                    )
                }
            }
            if (voiceState != VoiceState.Idle) {
                VoicePanel(state = voiceState, onClose = onStopVoice)
            } else {
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        onSend(input.text)
                        input = TextFieldValue("")
                    },
                    onMic = {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) onStartVoice()
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
}

// ---- Header ---------------------------------------------------------------

@Composable
private fun TappyHeader(onOpenSetup: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 6.dp, bottom = 8.dp),
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TappyAvatar(size = 44.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tappy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                IconButton(
                    onClick = onOpenSetup,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Setup",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HairlineDivider()
        }
    }
}

@Composable
private fun TappyAvatar(size: androidx.compose.ui.unit.Dp) {
    // The Tappy logo glyph on its cream backdrop, ringed in brand purple.
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(com.agentchat.ui.theme.BackgroundLight)
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.tappy_glyph),
            contentDescription = null,
            modifier = Modifier.size(size * 0.66f),
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// ---- Banners & empty state --------------------------------------------------

@Composable
private fun ServiceDisabledBanner(onEnable: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tappy can't operate apps yet — turn on its Accessibility service.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Enable",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEnable)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TappyAvatar(size = 76.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            "Hey, I'm Tappy",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tell me what to do on your phone — I'll tap, type, and scroll for you. Try one:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        val samples = listOf(
            "@spotify play lofi beats" to AccentLime,
            "@uber book me a cab to Koramangala" to AccentOrange,
            "@whatsapp text Mom I'm on my way" to AccentPink,
            "@youtube search cooking shorts" to AccentLavender,
        )
        samples.forEach { (sample, accent) ->
            Surface(
                color = accent.copy(alpha = 0.22f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(vertical = 5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sample,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

// ---- Timestamps -------------------------------------------------------------

/** iMessage-style: show a centered timestamp when >1h passed since the previous message. */
private fun showTimestampBefore(previous: Message?, current: Message): Boolean {
    if (previous == null) return true
    return current.timestamp - previous.timestamp > 60 * 60 * 1000L
}

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateTimeFormat = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today " + timeFormat.format(Date(timestamp))
        isYesterday -> "Yesterday " + timeFormat.format(Date(timestamp))
        else -> dateTimeFormat.format(Date(timestamp))
    }
}

@Composable
private fun TimestampDivider(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            formatTimestamp(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Bubbles ------------------------------------------------------------------

@Composable
private fun MessageBubble(
    message: Message,
    isLastInGroup: Boolean,
    isLatest: Boolean,
    onStop: () -> Unit,
) {
    when (message.sender) {
        MessageSender.SYSTEM -> SystemNotice(message)
        else -> ChatBubbleRow(
            message,
            isLastInGroup = isLastInGroup,
            isLatest = isLatest,
            onStop = onStop,
        )
    }
}

@Composable
private fun SystemNotice(message: Message) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun ChatBubbleRow(
    message: Message,
    isLastInGroup: Boolean,
    isLatest: Boolean,
    onStop: () -> Unit,
) {
    val isUser = message.sender == MessageSender.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    // iMessage geometry: big radii everywhere, small radius on the tail corner
    // of the last bubble in a group.
    val tail = if (isLastInGroup) 6.dp else 20.dp
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else tail,
        bottomEnd = if (isUser) tail else 20.dp,
    )
    val isWorking = !isUser && message.status == MessageStatus.WORKING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLastInGroup) 8.dp else 0.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 296.dp)
                .clip(shape)
                .background(bubbleColor),
        ) {
            if (isWorking && message.text.isBlank()) {
                TypingDots(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            } else {
                Text(
                    message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        StatusCaption(message = message, isUser = isUser, isLatest = isLatest, onStop = onStop)
    }
}

/**
 * Small caption under the bubble for in-flight / error / confirmation states.
 * Live states (working, awaiting a reply) only make sense on the newest
 * message — older messages can be left in those statuses by ended runs.
 */
@Composable
private fun StatusCaption(
    message: Message,
    isUser: Boolean,
    isLatest: Boolean,
    onStop: () -> Unit,
) {
    if (isUser) return
    if (!isLatest && message.status != MessageStatus.ERROR) return
    when (message.status) {
        MessageStatus.WORKING -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, top = 3.dp),
        ) {
            TypingDots(dotSize = 4.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "Working",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "Stop",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        MessageStatus.ERROR -> Text(
            "Something went wrong",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 6.dp, top = 3.dp),
        )

        MessageStatus.AWAITING_CONFIRMATION -> Text(
            "Waiting for your reply",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, top = 3.dp),
        )

        else -> Unit
    }
}

/** iMessage typing indicator: three dots pulsing in sequence. */
@Composable
private fun TypingDots(modifier: Modifier = Modifier, dotSize: androidx.compose.ui.unit.Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(dotSize / 2)) {
        repeat(3) { index ->
            val distance = kotlin.math.abs(phase - index).coerceAtMost(
                kotlin.math.abs(phase - 3 - index),
            )
            val alpha = (1f - distance / 1.5f).coerceIn(0.25f, 1f)
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

// ---- Input bar -------------------------------------------------------------------

@Composable
private fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // iMessage-style rounded pill with the send button living inside it.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 7.dp),
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                "Message Tappy… try @spotify",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = mentionVisualTransformation(
                                MaterialTheme.colorScheme.primary,
                            ),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    if (value.text.isBlank()) {
                        RoundIconButton(
                            icon = Icons.Filled.Mic,
                            contentDescription = "Voice mode",
                            onClick = onMic,
                        )
                    } else {
                        RoundIconButton(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = "Send",
                            onClick = onSend,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Colors any @mention token in the input with the brand purple. Length is
 * unchanged, so offset mapping stays identity — cursor/selection stay correct.
 */
private val mentionRegex = Regex("@[A-Za-z0-9._]+")

private fun mentionVisualTransformation(color: Color): VisualTransformation =
    VisualTransformation { text ->
        val annotated = AnnotatedString.Builder(text.text).apply {
            mentionRegex.findAll(text.text).forEach { match ->
                addStyle(
                    SpanStyle(color = color, fontWeight = FontWeight.SemiBold),
                    match.range.first,
                    match.range.last + 1,
                )
            }
        }.toAnnotatedString()
        TransformedText(annotated, OffsetMapping.Identity)
    }

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}
