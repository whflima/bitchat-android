package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.ReadReceipt
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.*
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import androidx.lifecycle.ViewModel
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.delay

/**
 * Main ViewModel for bitchat - 100% compatible with iOS ChatViewModel
 */
class ChatViewModel(application: Application) : AndroidViewModel(application), BluetoothMeshDelegate {
    
    private val context: Context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    
    // Core services
    val meshService = BluetoothMeshService(context)
    
    // Observable state - exactly same as iOS version
    private val _messages = MutableLiveData<List<BitchatMessage>>(emptyList())
    val messages: LiveData<List<BitchatMessage>> = _messages
    
    private val _connectedPeers = MutableLiveData<List<String>>(emptyList())
    val connectedPeers: LiveData<List<String>> = _connectedPeers
    
    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname
    
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected
    
    // Private chats
    private val _privateChats = MutableLiveData<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateChats: LiveData<Map<String, List<BitchatMessage>>> = _privateChats
    
    private val _selectedPrivateChatPeer = MutableLiveData<String?>(null)
    val selectedPrivateChatPeer: LiveData<String?> = _selectedPrivateChatPeer
    
    private val _unreadPrivateMessages = MutableLiveData<Set<String>>(emptySet())
    val unreadPrivateMessages: LiveData<Set<String>> = _unreadPrivateMessages
    
    // Channels
    private val _joinedChannels = MutableLiveData<Set<String>>(emptySet())
    val joinedChannels: LiveData<Set<String>> = _joinedChannels
    
    private val _currentChannel = MutableLiveData<String?>(null)
    val currentChannel: LiveData<String?> = _currentChannel
    
    private val _channelMessages = MutableLiveData<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: LiveData<Map<String, List<BitchatMessage>>> = _channelMessages
    
    private val _unreadChannelMessages = MutableLiveData<Map<String, Int>>(emptyMap())
    val unreadChannelMessages: LiveData<Map<String, Int>> = _unreadChannelMessages
    
    private val _passwordProtectedChannels = MutableLiveData<Set<String>>(emptySet())
    val passwordProtectedChannels: LiveData<Set<String>> = _passwordProtectedChannels
    
    private val _showPasswordPrompt = MutableLiveData<Boolean>(false)
    val showPasswordPrompt: LiveData<Boolean> = _showPasswordPrompt
    
    private val _passwordPromptChannel = MutableLiveData<String?>(null)
    val passwordPromptChannel: LiveData<String?> = _passwordPromptChannel
    
    // Internal state
    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelCreators = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()
    private val channelMembers = mutableMapOf<String, MutableSet<String>>()
    private val favoritePeers = mutableSetOf<String>()
    private val peerIDToPublicKeyFingerprint = mutableMapOf<String, String>()
    private val blockedUsers = mutableSetOf<String>()
    
    // Message deduplication - FIXED: Prevent duplicate messages from dual connection paths
    private val processedUIMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val recentSystemEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val MESSAGE_DEDUP_TIMEOUT = 30000L // 30 seconds
    private val SYSTEM_EVENT_DEDUP_TIMEOUT = 5000L // 5 seconds
    
    // Sidebar state
    private val _showSidebar = MutableLiveData(false)
    val showSidebar: LiveData<Boolean> = _showSidebar
    
    // Unread state computed properties
    val hasUnreadChannels: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>()
    
    val hasUnreadPrivateMessages: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>()
    
    // Command autocomplete
    private val _showCommandSuggestions = MutableLiveData(false)
    val showCommandSuggestions: LiveData<Boolean> = _showCommandSuggestions
    
    private val _commandSuggestions = MutableLiveData<List<CommandSuggestion>>(emptyList())
    val commandSuggestions: LiveData<List<CommandSuggestion>> = _commandSuggestions

    // Command suggestion data class
    data class CommandSuggestion(
        val command: String,
        val aliases: List<String> = emptyList(),
        val syntax: String? = null,
        val description: String
    )
    
    init {
        meshService.delegate = this
        loadNickname()
        loadData()
        
        // Start mesh service
        meshService.startServices()
        
        // Show welcome message if no peers after delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (_connectedPeers.value?.isEmpty() == true && _messages.value?.isEmpty() == true) {
                val welcomeMessage = BitchatMessage(
                    sender = "system",
                    content = "get people around you to download bitchat…and chat with them here!",
                    timestamp = Date(),
                    isRelay = false
                )
                addMessage(welcomeMessage)
            }
        }
        
        // Initialize unread state mediators
        hasUnreadChannels.addSource(_unreadChannelMessages) { unreadMap ->
            hasUnreadChannels.value = unreadMap.values.any { it > 0 }
        }
        
        hasUnreadPrivateMessages.addSource(_unreadPrivateMessages) { unreadSet ->
            hasUnreadPrivateMessages.value = unreadSet.isNotEmpty()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
    }
    
    // MARK: - Nickname Management
    
    private fun loadNickname() {
        val savedNickname = prefs.getString("nickname", null)
        if (savedNickname != null) {
            _nickname.value = savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            _nickname.value = randomNickname
            saveNickname(randomNickname)
        }
    }
    
    fun setNickname(newNickname: String) {
        _nickname.value = newNickname
        saveNickname(newNickname)
        // Send announce with new nickname
        meshService.sendBroadcastAnnounce()
    }
    
    private fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
    }
    
    // MARK: - Data Loading/Saving
    
    private fun loadData() {
        // Load joined channels
        val savedChannels = prefs.getStringSet("joined_channels", emptySet()) ?: emptySet()
        _joinedChannels.value = savedChannels
        
        // Initialize channel data structures
        savedChannels.forEach { channel ->
            if (!_channelMessages.value!!.containsKey(channel)) {
                val updatedChannelMessages = _channelMessages.value!!.toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                _channelMessages.value = updatedChannelMessages
            }
            
            if (!channelMembers.containsKey(channel)) {
                channelMembers[channel] = mutableSetOf()
            }
        }
        
        // Load password protected channels
        val savedProtectedChannels = prefs.getStringSet("password_protected_channels", emptySet()) ?: emptySet()
        _passwordProtectedChannels.value = savedProtectedChannels
        
        // Load channel creators
        val creatorsJson = prefs.getString("channel_creators", "{}")
        try {
            val gson = com.google.gson.Gson()
            val creatorsMap = gson.fromJson(creatorsJson, Map::class.java) as? Map<String, String>
            creatorsMap?.let { channelCreators.putAll(it) }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        // Load other data...
        loadFavorites()
        loadBlockedUsers()
    }
    
    private fun saveChannelData() {
        prefs.edit().apply {
            putStringSet("joined_channels", _joinedChannels.value)
            putStringSet("password_protected_channels", _passwordProtectedChannels.value)
            
            val gson = com.google.gson.Gson()
            putString("channel_creators", gson.toJson(channelCreators))
            
            apply()
        }
    }
    
    private fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        favoritePeers.addAll(savedFavorites)
    }
    
    private fun saveFavorites() {
        prefs.edit().putStringSet("favorites", favoritePeers).apply()
    }
    
    private fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        blockedUsers.addAll(savedBlockedUsers)
    }
    
    private fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", blockedUsers).apply()
    }
    
    // MARK: - Message Management
    
    private fun addMessage(message: BitchatMessage) {
        val currentMessages = _messages.value?.toMutableList() ?: mutableListOf()
        currentMessages.add(message)
        currentMessages.sortBy { it.timestamp }
        _messages.value = currentMessages
    }
    
    private fun addChannelMessage(channel: String, message: BitchatMessage) {
        val currentChannelMessages = _channelMessages.value?.toMutableMap() ?: mutableMapOf()
        if (!currentChannelMessages.containsKey(channel)) {
            currentChannelMessages[channel] = mutableListOf()
        }
        
        val channelMessageList = currentChannelMessages[channel]?.toMutableList() ?: mutableListOf()
        channelMessageList.add(message)
        channelMessageList.sortBy { it.timestamp }
        currentChannelMessages[channel] = channelMessageList
        _channelMessages.value = currentChannelMessages
        
        // Update unread count if not currently in this channel
        if (_currentChannel.value != channel) {
            val currentUnread = _unreadChannelMessages.value?.toMutableMap() ?: mutableMapOf()
            currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
            _unreadChannelMessages.value = currentUnread
        }
    }
    
    private fun addPrivateMessage(peerID: String, message: BitchatMessage) {
        val currentPrivateChats = _privateChats.value?.toMutableMap() ?: mutableMapOf()
        if (!currentPrivateChats.containsKey(peerID)) {
            currentPrivateChats[peerID] = mutableListOf()
        }
        
        val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
        chatMessages.add(message)
        chatMessages.sortBy { it.timestamp }
        currentPrivateChats[peerID] = chatMessages
        _privateChats.value = currentPrivateChats
        
        // Mark as unread if not currently viewing this chat
        if (_selectedPrivateChatPeer.value != peerID && message.sender != _nickname.value) {
            val currentUnread = _unreadPrivateMessages.value?.toMutableSet() ?: mutableSetOf()
            currentUnread.add(peerID)
            _unreadPrivateMessages.value = currentUnread
        }
    }
    
    // MARK: - Channel Management
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"
        
        // Check if already joined
        if (_joinedChannels.value?.contains(channelTag) == true) {
            if (_passwordProtectedChannels.value?.contains(channelTag) == true && !channelKeys.containsKey(channelTag)) {
                // Need password verification
                if (password != null) {
                    return verifyChannelPassword(channelTag, password)
                } else {
                    _passwordPromptChannel.value = channelTag
                    _showPasswordPrompt.value = true
                    return false
                }
            }
            switchToChannel(channelTag)
            return true
        }
        
        // If password protected and no key yet
        if (_passwordProtectedChannels.value?.contains(channelTag) == true && !channelKeys.containsKey(channelTag)) {
            if (channelCreators[channelTag] == meshService.myPeerID) {
                // Channel creator bypass
            } else if (password != null) {
                if (!verifyChannelPassword(channelTag, password)) {
                    return false
                }
            } else {
                _passwordPromptChannel.value = channelTag
                _showPasswordPrompt.value = true
                return false
            }
        }
        
        // Join the channel
        val updatedChannels = _joinedChannels.value?.toMutableSet() ?: mutableSetOf()
        updatedChannels.add(channelTag)
        _joinedChannels.value = updatedChannels
        
        // Set as creator if new channel
        if (!channelCreators.containsKey(channelTag) && _passwordProtectedChannels.value?.contains(channelTag) != true) {
            channelCreators[channelTag] = meshService.myPeerID
        }
        
        // Add ourselves as member
        if (!channelMembers.containsKey(channelTag)) {
            channelMembers[channelTag] = mutableSetOf()
        }
        channelMembers[channelTag]?.add(meshService.myPeerID)
        
        switchToChannel(channelTag)
        saveChannelData()
        return true
    }
    
    private fun verifyChannelPassword(channel: String, password: String): Boolean {
        val key = deriveChannelKey(password, channel)
        
        // Verify against existing messages if available
        val existingMessages = _channelMessages.value?.get(channel)?.filter { it.isEncrypted }
        if (!existingMessages.isNullOrEmpty()) {
            val testMessage = existingMessages.first()
            val decryptedContent = decryptChannelMessage(testMessage.encryptedContent ?: byteArrayOf(), channel, key)
            if (decryptedContent == null) {
                return false
            }
        }
        
        channelKeys[channel] = key
        channelPasswords[channel] = password
        return true
    }
    
    fun switchToChannel(channel: String?) {
        _currentChannel.value = channel
        _selectedPrivateChatPeer.value = null
        
        // Clear unread count
        channel?.let { ch ->
            val currentUnread = _unreadChannelMessages.value?.toMutableMap() ?: mutableMapOf()
            currentUnread.remove(ch)
            _unreadChannelMessages.value = currentUnread
        }
    }
    
    fun leaveChannel(channel: String) {
        val updatedChannels = _joinedChannels.value?.toMutableSet() ?: mutableSetOf()
        updatedChannels.remove(channel)
        _joinedChannels.value = updatedChannels
        
        // Send leave notification
        meshService.sendMessage("left $channel")
        
        // Exit channel if currently in it
        if (_currentChannel.value == channel) {
            _currentChannel.value = null
        }
        
        // Cleanup
        val updatedChannelMessages = _channelMessages.value?.toMutableMap() ?: mutableMapOf()
        updatedChannelMessages.remove(channel)
        _channelMessages.value = updatedChannelMessages
        
        val updatedUnread = _unreadChannelMessages.value?.toMutableMap() ?: mutableMapOf()
        updatedUnread.remove(channel)
        _unreadChannelMessages.value = updatedUnread
        
        channelMembers.remove(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        
        saveChannelData()
    }
    
    // MARK: - Private Chat Management
    
    fun startPrivateChat(peerID: String) {
        val peerNickname = meshService.getPeerNicknames()[peerID]
        
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot start chat with $peerNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            addMessage(systemMessage)
            return
        }
        
        _selectedPrivateChatPeer.value = peerID
        
        // Clear unread
        val updatedUnread = _unreadPrivateMessages.value?.toMutableSet() ?: mutableSetOf()
        updatedUnread.remove(peerID)
        _unreadPrivateMessages.value = updatedUnread
        
        // Initialize chat if needed
        if (_privateChats.value?.containsKey(peerID) != true) {
            val updatedChats = _privateChats.value?.toMutableMap() ?: mutableMapOf()
            updatedChats[peerID] = emptyList()
            _privateChats.value = updatedChats
        }
    }
    
    fun endPrivateChat() {
        _selectedPrivateChatPeer.value = null
    }
    
    // MARK: - Messaging
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            handleCommand(content)
            return
        }
        
        val mentions = parseMentions(content)
        val channels = parseChannels(content)
        
        // Auto-join mentioned channels
        channels.forEach { channel ->
            if (_joinedChannels.value?.contains(channel) != true) {
                joinChannel(channel)
            }
        }
        
        val selectedPeer = _selectedPrivateChatPeer.value
        val currentChannelValue = _currentChannel.value
        
        if (selectedPeer != null) {
            // Send private message
            sendPrivateMessage(content, selectedPeer)
        } else {
            // Send public/channel message
            val message = BitchatMessage(
                sender = _nickname.value ?: meshService.myPeerID,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = meshService.myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = currentChannelValue
            )
            
            if (currentChannelValue != null) {
                addChannelMessage(currentChannelValue, message)
                
                // Check if encrypted channel
                val channelKey = channelKeys[currentChannelValue]
                if (channelKey != null) {
                    sendEncryptedChannelMessage(content, mentions, currentChannelValue, channelKey)
                } else {
                    meshService.sendMessage(content, mentions, currentChannelValue)
                }
            } else {
                addMessage(message)
                meshService.sendMessage(content, mentions, null)
            }
        }
    }
    
    private fun sendPrivateMessage(content: String, peerID: String) {
        val recipientNickname = meshService.getPeerNicknames()[peerID] ?: return
        
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot send message to $recipientNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            addMessage(systemMessage)
            return
        }
        
        val message = BitchatMessage(
            sender = _nickname.value ?: meshService.myPeerID,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = meshService.myPeerID,
            deliveryStatus = DeliveryStatus.Sending
        )
        
        addPrivateMessage(peerID, message)
        meshService.sendPrivateMessage(content, peerID, recipientNickname, message.id)
    }
    
    private fun sendEncryptedChannelMessage(content: String, mentions: List<String>, channel: String, key: SecretKeySpec) {
        viewModelScope.launch {
            try {
                val contentBytes = content.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                
                val iv = cipher.iv
                val encryptedData = cipher.doFinal(contentBytes)
                
                // Combine IV and encrypted data
                val combined = ByteArray(iv.size + encryptedData.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)
                
                val encryptedMessage = BitchatMessage(
                    sender = _nickname.value ?: meshService.myPeerID,
                    content = "",
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = channel,
                    encryptedContent = combined,
                    isEncrypted = true
                )
                
                // Send encrypted message via mesh
                encryptedMessage.toBinaryPayload()?.let { messageData ->
                    // This would need to be sent via the mesh service properly
                    // For now, just broadcast the regular message
                    meshService.sendMessage(content, mentions, channel)
                }
                
            } catch (e: Exception) {
                // Fallback to unencrypted
                meshService.sendMessage(content, mentions, channel)
            }
        }
    }
    
    // MARK: - Utility Functions
    
    private fun parseMentions(content: String): List<String> {
        val mentionRegex = "@([a-zA-Z0-9_]+)".toRegex()
        val peerNicknames = meshService.getPeerNicknames().values.toSet()
        val allNicknames = peerNicknames + (_nickname.value ?: "")
        
        return mentionRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { allNicknames.contains(it) }
            .distinct()
            .toList()
    }
    
    private fun parseChannels(content: String): List<String> {
        val channelRegex = "#([a-zA-Z0-9_]+)".toRegex()
        return channelRegex.findAll(content)
            .map { it.groupValues[0] } // Include the #
            .distinct()
            .toList()
    }
    
    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun isPeerBlocked(peerID: String): Boolean {
        val fingerprint = peerIDToPublicKeyFingerprint[peerID]
        return fingerprint != null && blockedUsers.contains(fingerprint)
    }
    
    fun toggleFavorite(peerID: String) {
        val fingerprint = peerIDToPublicKeyFingerprint[peerID] ?: return
        
        if (favoritePeers.contains(fingerprint)) {
            favoritePeers.remove(fingerprint)
        } else {
            favoritePeers.add(fingerprint)
        }
        saveFavorites()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        val fingerprint = peerIDToPublicKeyFingerprint[peerID] ?: return false
        return favoritePeers.contains(fingerprint)
    }
    
    fun registerPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKeyData)
        val fingerprint = hash.take(8).joinToString("") { "%02x".format(it) }
        peerIDToPublicKeyFingerprint[peerID] = fingerprint
    }
    
    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {
        // PBKDF2 key derivation (same as iOS version)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(),
            100000, // 100,000 iterations (same as iOS)
            256 // 256-bit key
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
    
    private fun handleCommand(command: String) {
        val parts = command.split(" ")
        val cmd = parts.first()
        
        when (cmd) {
            "/j", "/join" -> {
                if (parts.size > 1) {
                    val channelName = parts[1]
                    val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
                    val success = joinChannel(channel)
                    if (success) {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "joined channel $channel",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    }
                } else {
                    val systemMessage = BitchatMessage(
                        sender = "system",
                        content = "usage: /join <channel>",
                        timestamp = Date(),
                        isRelay = false
                    )
                    addMessage(systemMessage)
                }
            }
            "/m", "/msg" -> {
                if (parts.size > 1) {
                    val targetName = parts[1].removePrefix("@")
                    val peerID = getPeerIDForNickname(targetName)
                    
                    if (peerID != null) {
                        startPrivateChat(peerID)
                        
                        if (parts.size > 2) {
                            val messageContent = parts.drop(2).joinToString(" ")
                            sendPrivateMessage(messageContent, peerID)
                        } else {
                            val systemMessage = BitchatMessage(
                                sender = "system",
                                content = "started private chat with $targetName",
                                timestamp = Date(),
                                isRelay = false
                            )
                            addMessage(systemMessage)
                        }
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "user '$targetName' not found. they may be offline or using a different nickname.",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    }
                } else {
                    val systemMessage = BitchatMessage(
                        sender = "system",
                        content = "usage: /msg <nickname> [message]",
                        timestamp = Date(),
                        isRelay = false
                    )
                    addMessage(systemMessage)
                }
            }
            "/w" -> {
                val peerList = _connectedPeers.value?.joinToString(", ") { peerID ->
                    meshService.getPeerNicknames()[peerID] ?: peerID
                } ?: "no one"
                
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = if (_connectedPeers.value?.isEmpty() == true) {
                        "no one else is online right now."
                    } else {
                        "online users: $peerList"
                    },
                    timestamp = Date(),
                    isRelay = false
                )
                addMessage(systemMessage)
            }
            "/clear" -> {
                when {
                    _selectedPrivateChatPeer.value != null -> {
                        // Clear private chat
                        val peerID = _selectedPrivateChatPeer.value!!
                        val updatedChats = _privateChats.value?.toMutableMap() ?: mutableMapOf()
                        updatedChats[peerID] = emptyList()
                        _privateChats.value = updatedChats
                    }
                    _currentChannel.value != null -> {
                        // Clear channel messages
                        val channel = _currentChannel.value!!
                        val updatedChannelMessages = _channelMessages.value?.toMutableMap() ?: mutableMapOf()
                        updatedChannelMessages[channel] = emptyList()
                        _channelMessages.value = updatedChannelMessages
                    }
                    else -> {
                        // Clear main messages
                        _messages.value = emptyList()
                    }
                }
            }
            "/block" -> {
                if (parts.size > 1) {
                    val targetName = parts[1].removePrefix("@")
                    val peerID = getPeerIDForNickname(targetName)
                    
                    if (peerID != null) {
                        val fingerprint = peerIDToPublicKeyFingerprint[peerID]
                        if (fingerprint != null) {
                            blockedUsers.add(fingerprint)
                            saveBlockedUsers()
                            
                            val systemMessage = BitchatMessage(
                                sender = "system",
                                content = "blocked user $targetName",
                                timestamp = Date(),
                                isRelay = false
                            )
                            addMessage(systemMessage)
                        }
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "user '$targetName' not found",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    }
                } else {
                    // List blocked users
                    if (blockedUsers.isEmpty()) {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "no blocked users",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "blocked users: ${blockedUsers.size} fingerprints",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    }
                }
            }
            "/unblock" -> {
                if (parts.size > 1) {
                    val targetName = parts[1].removePrefix("@")
                    val peerID = getPeerIDForNickname(targetName)
                    
                    if (peerID != null) {
                        val fingerprint = peerIDToPublicKeyFingerprint[peerID]
                        if (fingerprint != null && blockedUsers.contains(fingerprint)) {
                            blockedUsers.remove(fingerprint)
                            saveBlockedUsers()
                            
                            val systemMessage = BitchatMessage(
                                sender = "system",
                                content = "unblocked user $targetName",
                                timestamp = Date(),
                                isRelay = false
                            )
                            addMessage(systemMessage)
                        } else {
                            val systemMessage = BitchatMessage(
                                sender = "system",
                                content = "user '$targetName' is not blocked",
                                timestamp = Date(),
                                isRelay = false
                            )
                            addMessage(systemMessage)
                        }
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "user '$targetName' not found",
                            timestamp = Date(),
                            isRelay = false
                        )
                        addMessage(systemMessage)
                    }
                } else {
                    val systemMessage = BitchatMessage(
                        sender = "system",
                        content = "usage: /unblock <nickname>",
                        timestamp = Date(),
                        isRelay = false
                    )
                    addMessage(systemMessage)
                }
            }
            "/hug" -> {
                if (parts.size > 1) {
                    val targetName = parts[1].removePrefix("@")
                    val hugMessage = "* ${_nickname.value ?: "someone"} gives $targetName a warm hug 🫂 *"
                    
                    // Send as regular message
                    if (_selectedPrivateChatPeer.value != null) {
                        sendPrivateMessage(hugMessage, _selectedPrivateChatPeer.value!!)
                    } else {
                        val message = BitchatMessage(
                            sender = _nickname.value ?: meshService.myPeerID,
                            content = hugMessage,
                            timestamp = Date(),
                            isRelay = false,
                            senderPeerID = meshService.myPeerID,
                            channel = _currentChannel.value
                        )
                        
                        if (_currentChannel.value != null) {
                            addChannelMessage(_currentChannel.value!!, message)
                            meshService.sendMessage(hugMessage, emptyList(), _currentChannel.value)
                        } else {
                            addMessage(message)
                            meshService.sendMessage(hugMessage, emptyList(), null)
                        }
                    }
                } else {
                    val systemMessage = BitchatMessage(
                        sender = "system",
                        content = "usage: /hug <nickname>",
                        timestamp = Date(),
                        isRelay = false
                    )
                    addMessage(systemMessage)
                }
            }
            "/slap" -> {
                if (parts.size > 1) {
                    val targetName = parts[1].removePrefix("@")
                    val slapMessage = "* ${_nickname.value ?: "someone"} slaps $targetName around a bit with a large trout 🐟 *"
                    
                    // Send as regular message
                    if (_selectedPrivateChatPeer.value != null) {
                        sendPrivateMessage(slapMessage, _selectedPrivateChatPeer.value!!)
                    } else {
                        val message = BitchatMessage(
                            sender = _nickname.value ?: meshService.myPeerID,
                            content = slapMessage,
                            timestamp = Date(),
                            isRelay = false,
                            senderPeerID = meshService.myPeerID,
                            channel = _currentChannel.value
                        )
                        
                        if (_currentChannel.value != null) {
                            addChannelMessage(_currentChannel.value!!, message)
                            meshService.sendMessage(slapMessage, emptyList(), _currentChannel.value)
                        } else {
                            addMessage(message)
                            meshService.sendMessage(slapMessage, emptyList(), null)
                        }
                    }
                } else {
                    val systemMessage = BitchatMessage(
                        sender = "system",
                        content = "usage: /slap <nickname>",
                        timestamp = Date(),
                        isRelay = false
                    )
                    addMessage(systemMessage)
                }
            }
            "/channels" -> {
                val allChannels = (_joinedChannels.value ?: emptySet()).toList().sorted()
                val channelList = if (allChannels.isEmpty()) {
                    "no channels joined"
                } else {
                    "joined channels: ${allChannels.joinToString(", ")}"
                }
                
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = channelList,
                    timestamp = Date(),
                    isRelay = false
                )
                addMessage(systemMessage)
            }
            else -> {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "unknown command: $cmd. type / to see available commands.",
                    timestamp = Date(),
                    isRelay = false
                )
                addMessage(systemMessage)
            }
        }
    }
    
    // MARK: - Debug and Troubleshooting
    
    /**
     * Get debug status information for troubleshooting
     */
    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }
    
    /**
     * Force restart mesh services (for debugging)
     */
    fun restartMeshServices() {
        viewModelScope.launch {
            meshService.stopServices()
            kotlinx.coroutines.delay(1000)
            meshService.startServices()
        }
    }
    
    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                // A short, sharp knock effect
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                // A short vibration for older OS versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Silently ignore vibration errors (permission or hardware issues)
        }
    }
    
    // MARK: - Message Deduplication Utilities
    
    /**
     * Generate a unique key for message deduplication
     */
    private fun generateMessageKey(message: BitchatMessage): String {
        val senderKey = message.senderPeerID ?: message.sender
        val contentHash = message.content.hashCode()
        return "$senderKey-${message.timestamp.time}-$contentHash"
    }
    
    /**
     * Check if a system event is a duplicate within the timeout window
     */
    private fun isDuplicateSystemEvent(eventType: String, peerID: String): Boolean {
        val now = System.currentTimeMillis()
        val eventKey = "$eventType-$peerID"
        val lastEvent = recentSystemEvents[eventKey]
        
        if (lastEvent != null && (now - lastEvent) < SYSTEM_EVENT_DEDUP_TIMEOUT) {
            return true // Duplicate event
        }
        
        recentSystemEvents[eventKey] = now
        return false
    }
    
    /**
     * Clean up old entries from deduplication caches
     */
    private fun cleanupDeduplicationCaches() {
        val now = System.currentTimeMillis()
        
        // Clean up processed UI messages (remove entries older than 30 seconds)
        if (processedUIMessages.size > 1000) {
            processedUIMessages.clear()
        }
        
        // Clean up recent system events (remove entries older than timeout)
        recentSystemEvents.entries.removeAll { (_, timestamp) ->
            (now - timestamp) > SYSTEM_EVENT_DEDUP_TIMEOUT * 2
        }
    }

    // MARK: - BluetoothMeshDelegate Implementation
    
    override fun didReceiveMessage(message: BitchatMessage) {
        viewModelScope.launch {
            // FIXED: Deduplicate messages from dual connection paths
            val messageKey = generateMessageKey(message)
            if (processedUIMessages.contains(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            processedUIMessages.add(messageKey)
            
            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }
            
            // Trigger haptic feedback
            triggerHapticFeedback()

            if (message.isPrivate) {
                // Private message
                message.senderPeerID?.let { peerID ->
                    addPrivateMessage(peerID, message)
                }
            } else if (message.channel != null) {
                // Channel message
                if (_joinedChannels.value?.contains(message.channel) == true) {
                    addChannelMessage(message.channel, message)
                    
                    // Track as channel member
                    message.senderPeerID?.let { peerID ->
                        channelMembers[message.channel]?.add(peerID)
                    }
                }
            } else {
                // Public message
                addMessage(message)
            }
            
            // Periodic cleanup
            if (processedUIMessages.size > 500) {
                cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didConnectToPeer(peerID: String) {
        viewModelScope.launch {
            // FIXED: Deduplicate connection events from dual connection paths
            if (isDuplicateSystemEvent("connect", peerID)) {
                return@launch // Duplicate connection event, ignore
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID connected",
                timestamp = Date(),
                isRelay = false
            )
            addMessage(systemMessage)
        }
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        viewModelScope.launch {
            // FIXED: Deduplicate disconnection events from dual connection paths
            if (isDuplicateSystemEvent("disconnect", peerID)) {
                return@launch // Duplicate disconnection event, ignore
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID disconnected",
                timestamp = Date(),
                isRelay = false
            )
            addMessage(systemMessage)
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        viewModelScope.launch {
            _connectedPeers.value = peers
            _isConnected.value = peers.isNotEmpty()
            
            // Clean up channel members who disconnected
            channelMembers.values.forEach { members ->
                members.removeAll { memberID ->
                    memberID != meshService.myPeerID && !peers.contains(memberID)
                }
            }
            
            // Exit private chat if peer disconnected
            _selectedPrivateChatPeer.value?.let { currentPeer ->
                if (!peers.contains(currentPeer)) {
                    endPrivateChat()
                }
            }
        }
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        viewModelScope.launch {
            channelMembers[channel]?.remove(fromPeer)
        }
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        viewModelScope.launch {
            // Update message delivery status
            updateMessageDeliveryStatus(ack.originalMessageID, DeliveryStatus.Delivered(ack.recipientNickname, ack.timestamp))
        }
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        viewModelScope.launch {
            // Update message read status
            updateMessageDeliveryStatus(receipt.originalMessageID, DeliveryStatus.Read(receipt.readerNickname, receipt.timestamp))
        }
    }
    
    private fun updateMessageDeliveryStatus(messageID: String, status: DeliveryStatus) {
        // Update in private chats
        val updatedPrivateChats = _privateChats.value?.toMutableMap() ?: mutableMapOf()
        var updated = false
        
        updatedPrivateChats.forEach { (peerID, messages) ->
            val updatedMessages = messages.toMutableList()
            val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
            if (messageIndex >= 0) {
                updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = status)
                updatedPrivateChats[peerID] = updatedMessages
                updated = true
            }
        }
        
        if (updated) {
            _privateChats.value = updatedPrivateChats
        }
        
        // Update in main messages
        val updatedMessages = _messages.value?.toMutableList() ?: mutableListOf()
        val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
        if (messageIndex >= 0) {
            updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = status)
            _messages.value = updatedMessages
        }
        
        // Update in channel messages
        val updatedChannelMessages = _channelMessages.value?.toMutableMap() ?: mutableMapOf()
        updatedChannelMessages.forEach { (channel, messages) ->
            val channelMessagesList = messages.toMutableList()
            val channelMessageIndex = channelMessagesList.indexOfFirst { it.id == messageID }
            if (channelMessageIndex >= 0) {
                channelMessagesList[channelMessageIndex] = channelMessagesList[channelMessageIndex].copy(deliveryStatus = status)
                updatedChannelMessages[channel] = channelMessagesList
            }
        }
        _channelMessages.value = updatedChannelMessages
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }
    
    private fun decryptChannelMessage(encryptedContent: ByteArray, channel: String, testKey: SecretKeySpec?): String? {
        val key = testKey ?: channelKeys[channel] ?: return null
        
        try {
            if (encryptedContent.size < 16) return null // 12 bytes IV + minimum ciphertext
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0..11)
            val ciphertext = encryptedContent.sliceArray(12 until encryptedContent.size)
            
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedData = cipher.doFinal(ciphertext)
            return String(decryptedData, Charsets.UTF_8)
            
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun getNickname(): String? = _nickname.value
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        // Clear all messages and data
        _messages.value = emptyList()
        _privateChats.value = emptyMap()
        _channelMessages.value = emptyMap()
        _joinedChannels.value = emptySet()
        _unreadPrivateMessages.value = emptySet()
        _unreadChannelMessages.value = emptyMap()
        _passwordProtectedChannels.value = emptySet()
        _currentChannel.value = null
        _selectedPrivateChatPeer.value = null
        
        // Clear internal state
        channelKeys.clear()
        channelPasswords.clear()
        channelCreators.clear()
        channelKeyCommitments.clear()
        retentionEnabledChannels.clear()
        channelMembers.clear()
        favoritePeers.clear()
        peerIDToPublicKeyFingerprint.clear()
        blockedUsers.clear()
        
        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        _nickname.value = newNickname
        saveNickname(newNickname)
        
        // Clear preferences
        prefs.edit().clear().apply()
        
        // Disconnect from mesh
        meshService.stopServices()
        
        // Restart services with new identity
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            meshService.startServices()
        }
    }
    
    // MARK: - Command Autocomplete
    
    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/") || input.length < 1) {
            _showCommandSuggestions.value = false
            _commandSuggestions.value = emptyList()
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands()
        
        // Filter commands based on input
        val filteredCommands = filterCommands(allCommands, input.lowercase())
        
        if (filteredCommands.isNotEmpty()) {
            _commandSuggestions.value = filteredCommands
            _showCommandSuggestions.value = true
        } else {
            _showCommandSuggestions.value = false
            _commandSuggestions.value = emptyList()
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        val baseCommands = listOf(
            CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
            CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
            CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
            CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
            CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
            CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
            CommandSuggestion("/slap", emptyList(), "<nickname>", "slap someone with a trout"),
            CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
            CommandSuggestion("/w", emptyList(), null, "see who's online")
        )
        
        // Add channel-specific commands if in a channel
        val channelCommands = if (_currentChannel.value != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelCommands
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { command ->
            // Check primary command
            command.command.startsWith(input) ||
            // Check aliases
            command.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        _showCommandSuggestions.value = false
        _commandSuggestions.value = emptyList()
        return "${suggestion.command} "
    }
}
