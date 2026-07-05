package com.agentchat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupSheet(
    serviceEnabled: Boolean,
    hasApiKey: Boolean,
    maskedKey: String?,
    model: String,
    hasDeepgramKey: Boolean,
    maskedDeepgramKey: String?,
    onSaveApiKey: (String) -> Unit,
    onSaveDeepgramKey: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Set up Tappy",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))

            // ---- Step 1: Anthropic API key ----
            Text(
                "1. Anthropic API key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            ApiKeyStatusRow(hasApiKey, maskedKey, model)
            Spacer(Modifier.height(12.dp))
            ApiKeyField(hasApiKey = hasApiKey, onSave = onSaveApiKey)

            Spacer(Modifier.height(28.dp))

            // ---- Step 2: Deepgram API key (voice mode) ----
            Text(
                "2. Deepgram API key (voice mode)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            KeyStatusRow(
                hasKey = hasDeepgramKey,
                savedLabel = "Key saved: ${maskedDeepgramKey ?: ""}",
                missingLabel = "No Deepgram key — voice mode disabled",
                subtitle = "Powers live speech-to-text and spoken replies",
            )
            Spacer(Modifier.height(12.dp))
            KeyField(
                hasKey = hasDeepgramKey,
                placeholder = "Deepgram API key",
                label = "Paste Deepgram API key",
                onSave = onSaveDeepgramKey,
            )

            Spacer(Modifier.height(28.dp))

            // ---- Step 3: Accessibility service ----
            Text(
                "3. Accessibility service",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            StatusRow(serviceEnabled)
            Spacer(Modifier.height(16.dp))

            Text(
                "To let Tappy operate other apps for you, enable its Accessibility service once:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                "Tap \"Open Accessibility Settings\" below",
                "Find \"Tappy\" under Installed / Downloaded apps",
                "Toggle it on and confirm the permission",
                "Come back here — the status turns green",
            ).forEachIndexed { index, step ->
                StepRow(number = index + 1, text = step)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Accessibility Settings")
            }
            Spacer(Modifier.height(28.dp))

            // ---- Step 4: Siri-style activation (optional) ----
            Text(
                "4. Activate from anywhere (optional)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Set Tappy as your digital assistant to launch voice mode with the " +
                    "assistant gesture (corner swipe or power-button long-press on supported " +
                    "phones). You can also add the \"Tappy Voice\" Quick Settings tile, or " +
                    "map the Samsung side key (Settings → Advanced features → Side button → " +
                    "Open app → Tappy).",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenAssistantSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Choose default assistant app")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Keys are encrypted on-device and only sent to Anthropic / Deepgram. The agent acts only when you send a command in chat or by voice.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ApiKeyStatusRow(hasApiKey: Boolean, maskedKey: String?, model: String) {
    KeyStatusRow(
        hasKey = hasApiKey,
        savedLabel = "Key saved: ${maskedKey ?: ""}",
        missingLabel = "No API key set",
        subtitle = "Model: $model",
    )
}

@Composable
private fun KeyStatusRow(
    hasKey: Boolean,
    savedLabel: String,
    missingLabel: String,
    subtitle: String,
) {
    val color = if (hasKey) Color(0xFF1DB954) else Color(0xFFE0574B)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (hasKey) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (hasKey) savedLabel else missingLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyField(hasApiKey: Boolean, onSave: (String) -> Unit) {
    KeyField(
        hasKey = hasApiKey,
        placeholder = "sk-ant-…",
        label = "Paste Anthropic API key",
        onSave = onSave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyField(
    hasKey: Boolean,
    placeholder: String,
    label: String,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onSave(value.trim())
                value = ""
            },
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (hasKey) "Replace key" else "Save key")
        }
    }
}

@Composable
private fun StatusRow(enabled: Boolean) {
    val color = if (enabled) Color(0xFF1DB954) else Color(0xFFE0574B)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (enabled) "Agent service is ENABLED" else "Agent service is DISABLED",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
