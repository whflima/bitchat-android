package com.bitchat.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    var showSidebar by remember { mutableStateOf(false) }
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
    
    var messageText by remember { mutableStateOf("") }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showAppInfo by remember { mutableStateOf(false) }
    var tripleClickCount by remember { mutableStateOf(0) }
    
    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }
    
    val isConnected by viewModel.isConnected.observeAsState(false)
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val unreadChannelMessages by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)
    
    // Determine what messages to show
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> messages
    }
    
    // Use WindowInsets to handle keyboard properly
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val headerHeight = 36.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
        ) {
            // Header spacer - creates space for the floating header
            Spacer(modifier = Modifier.height(headerHeight))
            
            // Messages area - takes up available space, will compress when keyboard appears
            Box(modifier = Modifier.weight(1f)) {
                MessagesList(
                    messages = displayMessages,
                    currentUserNickname = nickname,
                    meshService = viewModel.meshService,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Input area - stays at bottom
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.background,
                shadowElevation = 8.dp
            ) {
                Column {
                    Divider(color = colorScheme.outline.copy(alpha = 0.3f))
                    
                    // Command suggestions box
                    if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                        CommandSuggestionsBox(
                            suggestions = commandSuggestions,
                            onSuggestionClick = { suggestion ->
                                messageText = viewModel.selectCommandSuggestion(suggestion)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Divider(color = colorScheme.outline.copy(alpha = 0.2f))
                    }
                    
                    MessageInput(
                        value = messageText,
                        onValueChange = { newText ->
                            messageText = newText
                            viewModel.updateCommandSuggestions(newText)
                        },
                        onSend = {
                            if (messageText.trim().isNotEmpty()) {
                                viewModel.sendMessage(messageText.trim())
                                messageText = ""
                            }
                        },
                        selectedPrivatePeer = selectedPrivatePeer,
                        currentChannel = currentChannel,
                        nickname = nickname,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // Floating header - positioned absolutely at top, ignores keyboard
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .windowInsetsPadding(WindowInsets.statusBars), // Only respond to status bar
            color = colorScheme.background.copy(alpha = 0.95f),
            shadowElevation = 4.dp
        ) {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            selectedPrivatePeer != null -> {
                                // Private chat header
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.endPrivateChat() }
                                    ) {
                                        Text(
                                            text = "← back",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.primary
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.weight(1f))
                                    
                                    val peerNickname = viewModel.meshService.getPeerNicknames()[selectedPrivatePeer] ?: selectedPrivatePeer ?: "Unknown"
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🔒", fontSize = 16.sp) // Slightly larger
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = peerNickname,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color(0xFFFF8C00) // Orange
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.weight(1f))
                                    
                                    // Favorite button
                                    IconButton(
                                        onClick = { 
                                            selectedPrivatePeer?.let { peer ->
                                                viewModel.toggleFavorite(peer)
                                            }
                                        }
                                    ) {
                                        val peer = selectedPrivatePeer
                                        Text(
                                            text = if (peer != null && viewModel.isFavorite(peer)) "★" else "☆",
                                            color = if (peer != null && viewModel.isFavorite(peer)) Color.Yellow else colorScheme.primary,
                                            fontSize = 18.sp // Larger icon
                                        )
                                    }
                                }
                            }
                            currentChannel != null -> {
                                // Channel header
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.switchToChannel(null) }
                                    ) {
                                        Text(
                                            text = "← back",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.primary
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.weight(1f))
                                    
                                    Text(
                                        text = "channel: $currentChannel",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF0080FF), // Blue
                                        modifier = Modifier.clickable { showSidebar = true }
                                    )
                                    
                                    Spacer(modifier = Modifier.weight(1f))
                                    
                                    TextButton(
                                        onClick = { 
                                            currentChannel?.let { channel ->
                                                viewModel.leaveChannel(channel)
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = "leave",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Red
                                        )
                                    }
                                }
                            }
                            else -> {
                                // Main header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "bitchat*",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                tripleClickCount++
                                                if (tripleClickCount >= 3) {
                                                    tripleClickCount = 0
                                                    viewModel.panicClearAllData()
                                                } else {
                                                    showAppInfo = true
                                                }
                                            }
                                        )
                                        
                                        Spacer(modifier = Modifier.width(8.dp))
                                        
                                        NicknameEditor(
                                            value = nickname,
                                            onValueChange = viewModel::setNickname
                                        )
                                    }
                                    
                                    PeerCounter(
                                        connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                                        joinedChannels = joinedChannels,
                                        hasUnreadChannels = hasUnreadChannels,
                                        hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                                        isConnected = isConnected,
                                        onClick = { showSidebar = true }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
        
        // Divider under header
        Divider(
            color = colorScheme.outline.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = headerHeight)
                .zIndex(1f)
        )
        
        // Sidebar overlay
        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(250, easing = EaseInCubic)
            ) + fadeOut(animationSpec = tween(250)),
            modifier = Modifier.zIndex(2f) 
        ) {
            SidebarOverlay(
                viewModel = viewModel,
                onDismiss = { showSidebar = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    
    // Password dialog
    if (showPasswordDialog && passwordPromptChannel != null) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                passwordInput = ""
            },
            title = {
                Text(
                    text = "Enter Channel Password",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Channel $passwordPromptChannel is password protected. Enter the password to join.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (passwordInput.isNotEmpty()) {
                            val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                            if (success) {
                                showPasswordDialog = false
                                passwordInput = ""
                            }
                        }
                    }
                ) {
                    Text(
                        text = "Join",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        passwordInput = ""
                    }
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}

@Composable
private fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "@",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )
        
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { 
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier.widthIn(max = 100.dp)
        )
    }
}

@Composable
private fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    hasUnreadPrivateMessages: Set<String>,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onClick() }
    ) {
        if (hasUnreadChannels.values.any { it > 0 }) {
            Text(
                text = "#",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF0080FF),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        if (hasUnreadPrivateMessages.isNotEmpty()) {
            Text(
                text = "✉",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF8C00),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Connected peers",
            modifier = Modifier.size(16.dp),
            tint = if (isConnected) Color(0xFF00C851) else Color.Red
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${connectedPeers.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isConnected) Color(0xFF00C851) else Color.Red,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        
        if (joinedChannels.isNotEmpty()) {
            Text(
                text = " · ⧉ ${joinedChannels.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) Color(0xFF00C851) else Color.Red,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val colorScheme = MaterialTheme.colorScheme
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(messages) { message ->
            MessageItem(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Single text view for natural wrapping (like iOS)
        Text(
            text = formatMessageAsAnnotatedString(message, currentUserNickname, meshService, colorScheme),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible
        )
        
        // Delivery status for private messages
        if (message.isPrivate && message.sender == currentUserNickname) {
            message.deliveryStatus?.let { status ->
                DeliveryStatusIcon(status = status)
            }
        }
    }
}

@Composable
private fun formatMessageAsAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme
): androidx.compose.ui.text.AnnotatedString {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    
    // Timestamp
    val timestampColor = if (message.sender == "system") Color.Gray else colorScheme.primary.copy(alpha = 0.7f)
    builder.pushStyle(androidx.compose.ui.text.SpanStyle(
        color = timestampColor,
        fontSize = 12.sp
    ))
    builder.append("[${timeFormatter.format(message.timestamp)}] ")
    builder.pop()
    
    if (message.sender != "system") {
        // Sender
        val senderColor = when {
            message.sender == currentUserNickname -> colorScheme.primary
            else -> {
                val peerID = message.senderPeerID
                val rssi = peerID?.let { meshService.getPeerRSSI()[it] } ?: -60
                getRSSIColor(rssi)
            }
        }
        
        builder.pushStyle(androidx.compose.ui.text.SpanStyle(
            color = senderColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        ))
        builder.append("<@${message.sender}> ")
        builder.pop()
        
        // Message content with mentions and hashtags highlighted
        appendFormattedContent(builder, message.content, message.mentions, currentUserNickname, colorScheme)
        
    } else {
        // System message
        builder.pushStyle(androidx.compose.ui.text.SpanStyle(
            color = Color.Gray,
            fontSize = 12.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        ))
        builder.append("* ${message.content} *")
        builder.pop()
    }
    
    return builder.toAnnotatedString()
}

private fun appendFormattedContent(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    colorScheme: ColorScheme
) {
    val isMentioned = mentions?.contains(currentUserNickname) == true
    
    // Parse hashtags and mentions
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    val mentionPattern = "@([a-zA-Z0-9_]+)".toRegex()
    
    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()
    
    // Combine and sort all matches
    val allMatches = (hashtagMatches.map { it.range to "hashtag" } + 
                     mentionMatches.map { it.range to "mention" })
        .sortedBy { it.first.first }
    
    var lastEnd = 0
    
    for ((range, type) in allMatches) {
        // Add text before the match
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            builder.pushStyle(androidx.compose.ui.text.SpanStyle(
                color = colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
            ))
            builder.append(beforeText)
            builder.pop()
        }
        
        // Add the styled match
        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "hashtag" -> {
                builder.pushStyle(androidx.compose.ui.text.SpanStyle(
                    color = Color(0xFF0080FF), // Blue
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ))
            }
            "mention" -> {
                builder.pushStyle(androidx.compose.ui.text.SpanStyle(
                    color = Color(0xFFFF8C00), // Orange
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ))
            }
        }
        builder.append(matchText)
        builder.pop()
        
        lastEnd = range.last + 1
    }
    
    // Add remaining text
    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(androidx.compose.ui.text.SpanStyle(
            color = colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(remainingText)
        builder.pop()
    }
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            Text(
                text = "✓${status.reached}/${status.total}",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp), // Reduced padding
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prompt
        Text(
            text = when {
                selectedPrivatePeer != null -> "<@$nickname> →"
                currentChannel != null -> "<@$nickname> →"  // Could show if channel is encrypted
                else -> "<@$nickname>"
            },
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = when {
                selectedPrivatePeer != null -> Color(0xFFFF8C00) // Orange for private
                currentChannel != null -> Color(0xFFFF8C00) // Orange if encrypted channel
                else -> colorScheme.primary
            },
            fontFamily = FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Text input
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f)
        )
        
        Spacer(modifier = Modifier.width(8.dp)) // Reduced spacing
        
        // Send button - smaller with light green background
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(32.dp) // Reduced from 40dp
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp) // Reduced size
                    .background(
                        color = Color(0xFF00C851).copy(alpha = 0.15f), // Light green background
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↑",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), // Smaller arrow
                    color = Color(0xFF00C851) // Green arrow
                )
            }
        }
    }
}

@Composable
private fun SidebarOverlay(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptyList())
    val currentChannel by viewModel.currentChannel.observeAsState()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val nickname by viewModel.nickname.observeAsState("")
    
    // Get peer data from mesh service
    val peerNicknames = viewModel.meshService.getPeerNicknames()
    val peerRSSI = viewModel.meshService.getPeerRSSI()
    val myPeerID = viewModel.meshService.myPeerID
    
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .align(Alignment.CenterEnd)
                .clickable { /* Prevent dismissing when clicking sidebar */ }
        ) {
            // Grey vertical bar for visual continuity (matches iOS)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            )
            
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars) // Add status bar padding
            ) {
                // Header - match main toolbar height (matches iOS)
                Row(
                    modifier = Modifier
                        .height(36.dp) // Match reduced main header height
                        .fillMaxWidth()
                        .background(colorScheme.surface.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "YOUR NETWORK",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                
                Divider()
                
                // Scrollable content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Channels section
                    if (joinedChannels.isNotEmpty()) {
                        item {
                            ChannelsSection(
                                channels = joinedChannels.toList(), // Convert Set to List
                                currentChannel = currentChannel,
                                colorScheme = colorScheme,
                                onChannelClick = { channel ->
                                    viewModel.switchToChannel(channel)
                                    onDismiss()
                                },
                                onLeaveChannel = { channel ->
                                    viewModel.leaveChannel(channel)
                                }
                            )
                        }
                        
                        item {
                            Divider(
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    
                    // People section
                    item {
                        PeopleSection(
                            connectedPeers = connectedPeers,
                            peerNicknames = peerNicknames,
                            peerRSSI = peerRSSI,
                            nickname = nickname,
                            colorScheme = colorScheme,
                            selectedPrivatePeer = selectedPrivatePeer,
                            viewModel = viewModel,
                            onPrivateChatStart = { peerID ->
                                viewModel.startPrivateChat(peerID)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelsSection(
    channels: List<String>,
    currentChannel: String?,
    colorScheme: ColorScheme,
    onChannelClick: (String) -> Unit,
    onLeaveChannel: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Person, // Using Person icon as placeholder
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        channels.forEach { channel ->
            val isSelected = channel == currentChannel
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick(channel) }
                    .background(
                        if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#$channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                
                // Leave channel button
                IconButton(
                    onClick = { onLeaveChannel(channel) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Leave channel",
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeopleSection(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Person, // Using Person icon for people
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "PEOPLE",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        if (connectedPeers.isEmpty()) {
            Text(
                text = "No one connected",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        } else {
            // Sort peers: favorites first, then by nickname
            val sortedPeers = connectedPeers.sortedWith { peer1, peer2 ->
                val isFav1 = viewModel.isFavorite(peer1)
                val isFav2 = viewModel.isFavorite(peer2)
                
                when {
                    isFav1 && !isFav2 -> -1
                    !isFav1 && isFav2 -> 1
                    else -> {
                        val name1 = if (peer1 == nickname) "You" else (peerNicknames[peer1] ?: peer1)
                        val name2 = if (peer2 == nickname) "You" else (peerNicknames[peer2] ?: peer2)
                        name1.compareTo(name2, ignoreCase = true)
                    }
                }
            }
            
            sortedPeers.forEach { peerID ->
                val isSelected = peerID == selectedPrivatePeer
                val isFavorite = viewModel.isFavorite(peerID)
                val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: peerID)
                val signalStrength = peerRSSI[peerID] ?: 0
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPrivateChatStart(peerID) }
                        .background(
                            if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Signal strength indicators
                    Row(modifier = Modifier.width(24.dp)) {
                        repeat(3) { index ->
                            val opacity = when {
                                signalStrength >= (index + 1) * 33 -> 1f
                                else -> 0.2f
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 3.dp, height = (4 + index * 2).dp)
                                    .background(
                                        colorScheme.onSurface.copy(alpha = opacity),
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                            if (index < 2) Spacer(modifier = Modifier.width(2.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Favorite star
                    IconButton(
                        onClick = { viewModel.toggleFavorite(peerID) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            modifier = Modifier.size(16.dp),
                            tint = if (isFavorite) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

private fun getRSSIColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF00FF00) // Bright green
        rssi >= -60 -> Color(0xFF80FF00) // Green-yellow
        rssi >= -70 -> Color(0xFFFFFF00) // Yellow
        rssi >= -80 -> Color(0xFFFF8000) // Orange
        else -> Color(0xFFFF4444) // Red
    }
}

@Composable
private fun CommandSuggestionsBox(
    suggestions: List<ChatViewModel.CommandSuggestion>,
    onSuggestionClick: (ChatViewModel.CommandSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Column(
        modifier = modifier
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(vertical = 8.dp)
    ) {
        suggestions.forEach { suggestion ->
            CommandSuggestionItem(
                suggestion = suggestion,
                onClick = { onSuggestionClick(suggestion) }
            )
        }
    }
}

@Composable
private fun CommandSuggestionItem(
    suggestion: ChatViewModel.CommandSuggestion,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(Color.Gray.copy(alpha = 0.1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show all aliases together
        val allCommands = if (suggestion.aliases.isNotEmpty()) {
            listOf(suggestion.command) + suggestion.aliases
        } else {
            listOf(suggestion.command)
        }
        
        Text(
            text = allCommands.joinToString(", "),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = colorScheme.primary,
            fontSize = 11.sp
        )
        
        // Show syntax if any
        suggestion.syntax?.let { syntax ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = syntax,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 10.sp
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Show description
        Text(
            text = suggestion.description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
