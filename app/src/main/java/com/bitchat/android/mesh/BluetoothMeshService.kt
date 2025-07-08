package com.bitchat.android.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.crypto.MessagePadding
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Bluetooth mesh service - 100% compatible with iOS version
 * Uses exact same UUIDs, packet format, and protocol logic
 */
class BluetoothMeshService(private val context: Context) {
    
    companion object {
        // Exact same UUIDs as iOS version
        private val SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        private val CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        
        private const val TAG = "BluetoothMeshService"
        private const val MAX_TTL: UByte = 7u
        private const val MAX_FRAGMENT_SIZE = 500  // Optimized for BLE 5.0
        private const val MESSAGE_CACHE_TIMEOUT = 43200000L  // 12 hours for regular peers
        private const val MAX_CACHED_MESSAGES = 100  // For regular peers
        private const val MAX_CACHED_MESSAGES_FAVORITES = 1000  // For favorites
    }
    
    // Core networking components
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // Services for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    
    // Connected devices tracking - FIXED to properly track both server and client connections
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceCharacteristics = ConcurrentHashMap<BluetoothDevice, BluetoothGattCharacteristic>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private val gattConnections = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>() // NEW: Track GATT connections
    private val peripheralRSSI = ConcurrentHashMap<String, Int>() // Track RSSI by device address during discovery
    
    // Peer management
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val activePeers = ConcurrentHashMap<String, Long>()  // peerID -> lastSeen timestamp
    private val peerRSSI = ConcurrentHashMap<String, Int>()
    
    // Message processing
    private val encryptionService = EncryptionService(context)
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    
    // Store-and-forward message cache
    private data class StoredMessage(
        val packet: BitchatPacket,
        val timestamp: Long,
        val messageID: String,
        val isForFavorite: Boolean
    )
    private val messageCache = Collections.synchronizedList(mutableListOf<StoredMessage>())
    private val favoriteMessageQueue = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    private val deliveredMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val cachedMessagesSentToPeer = Collections.synchronizedSet(mutableSetOf<String>())
    
    // Fragment handling
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    
    // Privacy and security
    private val announcedToPeers = Collections.synchronizedSet(mutableSetOf<String>())
    private val announcedPeers = Collections.synchronizedSet(mutableSetOf<String>())
    private val blockedUsers = Collections.synchronizedSet(mutableSetOf<String>())
    
    // My peer identification - FIXED to match iOS format
    val myPeerID: String = generateCompatiblePeerID()
    
    // Delegate for message callbacks
    var delegate: BluetoothMeshDelegate? = null
    
    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // FIXED: Generate peer ID compatible with iOS
    private fun generateCompatiblePeerID(): String {
        val randomBytes = ByteArray(4)
        Random.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Start the mesh service - begins advertising and scanning - IMPROVED with better initialization
     */
    fun startServices() {
        Log.i(TAG, "Starting Bluetooth mesh service...")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions - cannot start services")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled - cannot start services")
            return
        }
        
        if (bleScanner == null || bleAdvertiser == null) {
            Log.e(TAG, "BLE scanner or advertiser not available")
            return
        }
        
        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")
        
        // Start services in sequence with proper error handling
        try {
            setupGattServer()
            
            // Small delay to ensure GATT server is ready
            serviceScope.launch {
                delay(500)
                
                startAdvertising()
                delay(200)
                
                startScanning()
                delay(200)
                
                // Send initial announcements after all services are ready
                delay(1000)
                sendBroadcastAnnounce()
                
                // Start periodic cleanup
                startPeriodicTasks()
                
                Log.i(TAG, "All Bluetooth mesh services started successfully")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth mesh services: ${e.message}")
        }
    }
    
    /**
     * Stop all mesh services - FIXED to properly clean up all connections
     */
    fun stopServices() {
        Log.i(TAG, "Stopping Bluetooth mesh service")
        
        // Send leave announcement before disconnecting
        sendLeaveAnnouncement()
        
        serviceScope.launch {
            delay(200) // Give leave message time to send
            
            // Cleanup all GATT client connections
            gattConnections.values.forEach { gatt ->
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing GATT connection: ${e.message}")
                }
            }
            
            // Stop advertising and scanning
            stopAdvertising()
            stopScanning()
            
            // Close GATT server
            gattServer?.close()
            
            // Clear all connection tracking
            connectedDevices.clear()
            deviceCharacteristics.clear()
            subscribedDevices.clear()
            gattConnections.clear() // FIXED: Clear GATT connections
            activePeers.clear()
            
            serviceScope.cancel()
        }
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
        
        return permissions.all { 
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    /**
     * Setup GATT server for peripheral mode
     */
    private fun setupGattServer() {
        if (!hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Device connected: ${device.address}")
                        
                        // FIXED: Check if this is a stale connection from previous app session
                        if (!subscribedDevices.contains(device)) {
                            Log.w(TAG, "Received stale connection from ${device.address}, disconnecting")
                            // Disconnect stale connections immediately
                            gattServer?.cancelConnection(device)
                            return
                        }
                        
                        connectedDevices[device.address] = device
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Device disconnected: ${device.address}")
                        connectedDevices.remove(device.address)
                        deviceCharacteristics.remove(device)
                        subscribedDevices.remove(device)
                    }
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    Log.d(TAG, "Received write request from ${device.address}, ${value.size} bytes")
                    
                    // Process received packet
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = String(packet.senderID).replace("\u0000", "")
                        handleReceivedPacket(packet, peerID, device)
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.d(TAG, "Device ${device.address} subscribed to notifications")
                    if (!subscribedDevices.contains(device)) {
                        subscribedDevices.add(device)
                        
                        // Send key exchange to newly connected device
                        serviceScope.launch {
                            delay(100) // Small delay to ensure connection is stable
                            sendKeyExchangeToDevice(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // FIXED: Clean up any existing GATT server to prevent stale connections
        gattServer?.close()
        
        // FIXED: Clear all connection tracking to start fresh
        connectedDevices.clear()
        deviceCharacteristics.clear() 
        subscribedDevices.clear()
        gattConnections.clear()
        
        Log.i(TAG, "Setting up fresh GATT server, cleared all previous connections")
        
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with same UUID as iOS
        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // Add notification descriptor
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        // Create service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
    }
    
    /**
     * Start BLE advertising - FIXED to exactly match iOS implementation using service data
     */
    private fun startAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) {
            Log.e(TAG, "Cannot start advertising: missing permissions or advertiser unavailable")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        // iOS uses: CBAdvertisementDataLocalNameKey: myPeerID
        // Android equivalent: addServiceData() with peer ID bytes
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), myPeerID.toByteArray(Charsets.UTF_8))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started successfully with peer ID in service data: $myPeerID")
            }
            
            override fun onStartFailure(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Unknown error: $errorCode"
                }
                Log.e(TAG, "Advertising failed: $errorMessage")
                
                // If this fails, try minimal advertising
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    Log.w(TAG, "Service data too large, trying minimal advertising")
                    startMinimalAdvertising()
                }
            }
        }
        
        Log.d(TAG, "Starting BLE advertising with peer ID in service data: $myPeerID")
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Fallback minimal advertising without peer ID if the main approach fails
     */
    private fun startMinimalAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) return
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        // Minimal advertisement - just the service UUID
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Minimal advertising started successfully (no peer ID in advertisement)")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Even minimal advertising failed: $errorCode")
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting minimal advertising: ${e.message}")
        }
    }
    
    /**
     * Stop BLE advertising
     */
    private fun stopAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) return
        
        bleAdvertiser.stopAdvertising(object : AdvertiseCallback() {})
    }
    
    /**
     * Start BLE scanning - FIXED for better peer discovery
     */
    private fun startScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null) {
            Log.e(TAG, "Cannot start scanning: missing permissions or scanner unavailable")
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        // FIXED: More aggressive scanning settings for better peer discovery
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // More aggressive scanning
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(10) // Report immediately
            .build()
        
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results: ${results.size} devices")
                results.forEach { handleScanResult(it) }
            }
            
            override fun onScanFailed(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    else -> "Unknown error: $errorCode"
                }
                Log.e(TAG, "Scan failed: $errorMessage")
            }
        }
        
        try {
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.i(TAG, "Started BLE scanning with aggressive settings")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
        }
    }
    
    /**
     * Stop BLE scanning
     */
    private fun stopScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null) return
        
        bleScanner.stopScan(object : ScanCallback() {})
    }
    
    /**
     * Handle scan result - connect to discovered devices - FIXED to connect regardless of peer ID extraction
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        
        Log.d(TAG, "Scan result: ${device.address}, RSSI: $rssi")
        
        // Filter out weak signals
        if (rssi < -90) {
            Log.d(TAG, "Ignoring weak signal from ${device.address}: $rssi dBm")
            return
        }
        
        // Check if already known and connected
        if (connectedDevices.values.any { it.address == device.address }) {
            Log.d(TAG, "Already connected to device: ${device.address}")
            return
        }
        
        // FIXED: Always attempt connection to devices advertising our service UUID
        // Peer ID will be obtained during key exchange, not from advertisements
        Log.i(TAG, "Found bitchat service at ${device.address} (RSSI: $rssi), attempting connection")
        
        // Store RSSI by device address for now (will be mapped to peer ID after key exchange)
        peripheralRSSI[device.address] = rssi
        
        // Attempt connection - peer ID will be determined during key exchange
        connectToDevice(device)
        
        // OPTIONAL: Still try to extract peer ID for optimization, but don't require it
        var discoveredPeerID: String? = null
        
        // Method 1: Try service data first (Android method - peer ID is in service data)
        val scanRecord = result.scanRecord
        val serviceData = scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
        if (serviceData != null && serviceData.isNotEmpty()) {
            try {
                val peerIdFromServiceData = String(serviceData, Charsets.UTF_8)
                if (peerIdFromServiceData.length == 8 && peerIdFromServiceData != myPeerID) {
                    discoveredPeerID = peerIdFromServiceData
                    Log.d(TAG, "Extracted peer ID from service data: $discoveredPeerID")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode service data as peer ID: ${e.message}")
            }
        }
        
        // Method 2: Try device name as fallback (iOS method compatibility)
        if (discoveredPeerID == null) {
            try {
                val deviceName = device.name
                if (!deviceName.isNullOrEmpty() && deviceName.length == 8 && deviceName != myPeerID) {
                    discoveredPeerID = deviceName
                    Log.d(TAG, "Extracted peer ID from device name (iOS compatibility): $discoveredPeerID")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to access device name")
            }
        }
        
        // If we extracted a peer ID, pre-populate tracking data
        if (discoveredPeerID != null) {
            Log.i(TAG, "Pre-discovered peer: $discoveredPeerID at ${device.address}")
            activePeers[discoveredPeerID] = System.currentTimeMillis()
            peerRSSI[discoveredPeerID] = rssi
        } else {
            Log.d(TAG, "No peer ID found in advertisements for ${device.address}, will get it via key exchange")
        }
    }
    
    /**
     * Connect to a discovered device
     */
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) return
        
        Log.d(TAG, "Connecting to device: ${device.address}")
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to ${gatt.device.address}")
                        // FIXED: Properly track the GATT connection
                        connectedDevices[gatt.device.address] = gatt.device
                        gattConnections[gatt.device] = gatt
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from ${gatt.device.address}")
                        connectedDevices.remove(gatt.device.address)
                        deviceCharacteristics.remove(gatt.device)
                        gattConnections.remove(gatt.device) // FIXED: Remove GATT connection tracking
                        gatt.close()
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    
                    if (characteristic != null) {
                        deviceCharacteristics[gatt.device] = characteristic
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Enable notifications
                        val descriptor = characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        
                        // Send key exchange
                        serviceScope.launch {
                            delay(200) // Ensure connection is stable
                            sendKeyExchangeToGatt(gatt)
                        }
                    }
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.d(TAG, "Received notification from ${gatt.device.address}, ${value.size} bytes")
                
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = String(packet.senderID).replace("\u0000", "")
                    handleReceivedPacket(packet, peerID, gatt.device)
                }
            }
            
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Successfully wrote to characteristic on ${gatt.device.address}")
                } else {
                    Log.w(TAG, "Failed to write to characteristic on ${gatt.device.address}, status: $status")
                }
            }
        }
        
        device.connectGatt(context, false, gattCallback)
    }
    
    /**
     * Send key exchange to a connected device (server mode)
     */
    private fun sendKeyExchangeToDevice(device: BluetoothDevice) {
        val publicKeyData = encryptionService.getCombinedPublicKeyData()
        val packet = BitchatPacket(
            type = MessageType.KEY_EXCHANGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = publicKeyData
        )
        
        packet.toBinaryData()?.let { data ->
            characteristic?.value = data
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            Log.d(TAG, "Sent key exchange to device: ${device.address}")
        }
    }
    
    /**
     * Send key exchange to a connected device (client mode)
     */
    private fun sendKeyExchangeToGatt(gatt: BluetoothGatt) {
        val publicKeyData = encryptionService.getCombinedPublicKeyData()
        val packet = BitchatPacket(
            type = MessageType.KEY_EXCHANGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = publicKeyData
        )
        
        val characteristic = deviceCharacteristics[gatt.device]
        if (characteristic != null) {
            packet.toBinaryData()?.let { data ->
                characteristic.value = data
                gatt.writeCharacteristic(characteristic)
                Log.d(TAG, "Sent key exchange to GATT: ${gatt.device.address}")
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private fun handleReceivedPacket(packet: BitchatPacket, peerID: String, device: BluetoothDevice? = null) {
        serviceScope.launch {
            // TTL check
            if (packet.ttl == 0u.toUByte()) return@launch
            
            // Validate packet payload
            if (packet.payload.isEmpty()) return@launch
            
            // Update last seen timestamp
            if (peerID != "unknown" && peerID != myPeerID) {
                activePeers[peerID] = System.currentTimeMillis()
            }
            
            // Replay attack protection (same 5-minute window as iOS)
            val currentTime = System.currentTimeMillis()
            val timeDiff = kotlin.math.abs(currentTime - packet.timestamp.toLong())
            if (timeDiff > 300000) { // 5 minutes
                Log.d(TAG, "Dropping old packet from $peerID, time diff: ${timeDiff/1000}s")
                return@launch
            }
            
            // Duplicate detection
            val messageID = generateMessageID(packet)
            if (processedMessages.contains(messageID)) {
                return@launch
            }
            processedMessages.add(messageID)
            
            // Process based on message type (exact same logic as iOS)
            when (MessageType.fromValue(packet.type)) {
                MessageType.KEY_EXCHANGE -> handleKeyExchange(packet, peerID, device)
                MessageType.ANNOUNCE -> handleAnnounce(packet, peerID)
                MessageType.MESSAGE -> handleMessage(packet, peerID)
                MessageType.LEAVE -> handleLeave(packet, peerID)
                MessageType.FRAGMENT_START,
                MessageType.FRAGMENT_CONTINUE,
                MessageType.FRAGMENT_END -> handleFragment(packet, peerID)
                MessageType.DELIVERY_ACK -> handleDeliveryAck(packet, peerID)
                MessageType.READ_RECEIPT -> handleReadReceipt(packet, peerID)
                else -> Log.d(TAG, "Unknown message type: ${packet.type}")
            }
        }
    }
    
    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: BitchatPacket): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT_START, MessageType.FRAGMENT_CONTINUE, MessageType.FRAGMENT_END -> {
                "${packet.timestamp}-${String(packet.senderID)}-${packet.type}-${packet.payload.contentHashCode()}"
            }
            else -> {
                "${packet.timestamp}-${String(packet.senderID)}-${packet.payload.sliceArray(0 until minOf(64, packet.payload.size)).contentHashCode()}"
            }
        }
    }
    
    /**
     * Handle key exchange message
     */
    private suspend fun handleKeyExchange(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
        if (peerID == myPeerID) return
        
        if (packet.payload.isNotEmpty()) {
            val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"
            
            if (processedKeyExchanges.contains(exchangeKey)) return
            processedKeyExchanges.add(exchangeKey)
            
            try {
                encryptionService.addPeerPublicKey(peerID, packet.payload)
                
                // Add to active peers
                activePeers[peerID] = System.currentTimeMillis()
                
                // Send our announce
                delay(100)
                sendAnnouncementToPeer(peerID)
                
                // Send cached messages for this peer
                delay(500)
                sendCachedMessages(peerID)
                
                Log.d(TAG, "Processed key exchange from $peerID")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process key exchange from $peerID: ${e.message}")
            }
        }
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(packet: BitchatPacket, peerID: String) {
        if (peerID == myPeerID) return
        
        val nickname = String(packet.payload, Charsets.UTF_8)
        Log.d(TAG, "Received announce from $peerID: $nickname")
        
        // Clean up stale peer IDs with the same nickname (exact same logic as iOS)
        val stalePeerIDs = mutableListOf<String>()
        peerNicknames.forEach { (existingPeerID, existingNickname) ->
            if (existingNickname == nickname && existingPeerID != peerID) {
                val lastSeen = activePeers[existingPeerID] ?: 0
                val wasRecentlySeen = (System.currentTimeMillis() - lastSeen) < 10000
                if (!wasRecentlySeen) {
                    stalePeerIDs.add(existingPeerID)
                }
            }
        }
        
        // Remove stale peer IDs
        stalePeerIDs.forEach { stalePeerID ->
            peerNicknames.remove(stalePeerID)
            activePeers.remove(stalePeerID)
            announcedPeers.remove(stalePeerID)
            peerRSSI.remove(stalePeerID)
        }
        
        // Add new peer
        val isFirstAnnounce = !announcedPeers.contains(peerID)
        peerNicknames[peerID] = nickname
        activePeers[peerID] = System.currentTimeMillis()
        
        if (isFirstAnnounce) {
            announcedPeers.add(peerID)
            delegate?.didConnectToPeer(nickname)
            notifyPeerListUpdate()
        }
        
        // Relay announce if TTL > 0
        if (packet.ttl > 1u) {
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            delay(Random.nextLong(100, 300))
            broadcastPacket(relayPacket)
        }
    }
    
    /**
     * Handle broadcast or private message
     */
    private suspend fun handleMessage(packet: BitchatPacket, peerID: String) {
        if (peerID == myPeerID) return
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(SpecialRecipients.BROADCAST) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            try {
                // Parse message
                val message = BitchatMessage.fromBinaryPayload(packet.payload)
                if (message != null) {
                    // Check for cover traffic (dummy messages)
                    if (message.content.startsWith("☂DUMMY☂")) {
                        return // Silently discard
                    }
                    
                    peerNicknames[peerID] = message.sender
                    
                    // Handle encrypted channel messages
                    val finalContent = if (message.channel != null && message.isEncrypted && message.encryptedContent != null) {
                        delegate?.decryptChannelMessage(message.encryptedContent, message.channel)
                        ?: "[Encrypted message - password required]"
                    } else {
                        message.content
                    }
                    
                    // Replace timestamp with current time
                    val messageWithCurrentTime = message.copy(
                        content = finalContent,
                        senderPeerID = peerID,
                        timestamp = Date() // Use current time instead of original timestamp
                    )
                    
                    delegate?.didReceiveMessage(messageWithCurrentTime)
                }
                
                // Relay broadcast messages
                relayMessage(packet)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process broadcast message: ${e.message}")
            }
            
        } else if (recipientID != null && String(recipientID).replace("\u0000", "") == myPeerID) {
            // PRIVATE MESSAGE FOR US
            try {
                // Verify signature if present
                packet.signature?.let { signature ->
                    if (!encryptionService.verify(signature, packet.payload, peerID)) {
                        Log.w(TAG, "Invalid signature for private message from $peerID")
                        return
                    }
                }
                
                // Decrypt message
                val decryptedData = encryptionService.decrypt(packet.payload, peerID)
                val unpaddedData = MessagePadding.unpad(decryptedData)
                
                // Parse message
                val message = BitchatMessage.fromBinaryPayload(unpaddedData)
                if (message != null) {
                    // Check for cover traffic (dummy messages)
                    if (message.content.startsWith("☂DUMMY☂")) {
                        return // Silently discard
                    }
                    
                    peerNicknames[peerID] = message.sender
        
                    // Replace timestamp with current time
                    val messageWithCurrentTime = message.copy(
                        senderPeerID = peerID,
                        timestamp = Date() // Use current time instead of original timestamp
                    )
                    delegate?.didReceiveMessage(messageWithCurrentTime)
                    
                    // Send delivery ACK
                    sendDeliveryAck(message, peerID)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt private message from $peerID: ${e.message}")
            }
            
        } else if (packet.ttl > 0u) {
            // RELAY MESSAGE
            relayMessage(packet)
        }
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(packet: BitchatPacket, peerID: String) {
        val content = String(packet.payload, Charsets.UTF_8)
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.didReceiveChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            activePeers.remove(peerID)
            announcedPeers.remove(peerID)
            peerNicknames[peerID]?.let { nickname ->
                delegate?.didDisconnectFromPeer(nickname)
            }
            peerNicknames.remove(peerID)
            notifyPeerListUpdate()
        }
        
        // Relay if TTL > 0
        if (packet.ttl > 1u) {
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            broadcastPacket(relayPacket)
        }
    }
    
    /**
     * Handle delivery acknowledgment
     */
    private suspend fun handleDeliveryAck(packet: BitchatPacket, peerID: String) {
        if (packet.recipientID != null && String(packet.recipientID).replace("\u0000", "") == myPeerID) {
            try {
                val decryptedData = encryptionService.decrypt(packet.payload, peerID)
                val ack = DeliveryAck.decode(decryptedData)
                if (ack != null) {
                    delegate?.didReceiveDeliveryAck(ack)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt delivery ACK: ${e.message}")
            }
        } else if (packet.ttl > 0u) {
            // Relay 
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            broadcastPacket(relayPacket)
        }
    }
    
    /**
     * Handle read receipt
     */
    private suspend fun handleReadReceipt(packet: BitchatPacket, peerID: String) {
        if (packet.recipientID != null && String(packet.recipientID).replace("\u0000", "") == myPeerID) {
            try {
                val decryptedData = encryptionService.decrypt(packet.payload, peerID)
                val receipt = ReadReceipt.decode(decryptedData)
                if (receipt != null) {
                    delegate?.didReceiveReadReceipt(receipt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt read receipt: ${e.message}")
            }
        } else if (packet.ttl > 0u) {
            // Relay
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            broadcastPacket(relayPacket)
        }
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(packet: BitchatPacket, peerID: String) {
        if (packet.payload.size < 13) return
        
        try {
            // Extract fragment metadata (same format as iOS)
            val fragmentIDData = packet.payload.sliceArray(0..7)
            val fragmentID = fragmentIDData.contentHashCode().toString()
            
            val index = ((packet.payload[8].toInt() and 0xFF) shl 8) or (packet.payload[9].toInt() and 0xFF)
            val total = ((packet.payload[10].toInt() and 0xFF) shl 8) or (packet.payload[11].toInt() and 0xFF)
            val originalType = packet.payload[12].toUByte()
            val fragmentData = packet.payload.sliceArray(13 until packet.payload.size)
            
            // Store fragment
            if (!incomingFragments.containsKey(fragmentID)) {
                incomingFragments[fragmentID] = mutableMapOf()
                fragmentMetadata[fragmentID] = Triple(originalType, total, System.currentTimeMillis())
            }
            
            incomingFragments[fragmentID]?.put(index, fragmentData)
            
            // Check if we have all fragments
            if (incomingFragments[fragmentID]?.size == total) {
                // Reassemble message
                val reassembledData = mutableListOf<Byte>()
                for (i in 0 until total) {
                    incomingFragments[fragmentID]?.get(i)?.let { data ->
                        reassembledData.addAll(data.asIterable())
                    }
                }
                
                // Parse and handle reassembled packet
                val reassembledPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                if (reassembledPacket != null) {
                    handleReceivedPacket(reassembledPacket, peerID)
                }
                
                // Cleanup
                incomingFragments.remove(fragmentID)
                fragmentMetadata.remove(fragmentID)
            }
            
            // Relay fragment
            if (packet.ttl > 0u) {
                val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
                broadcastPacket(relayPacket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }
        
        // Clean up old fragments (older than 30 seconds)
        val cutoffTime = System.currentTimeMillis() - 30000
        fragmentMetadata.entries.removeAll { (fragmentID, metadata) ->
            if (metadata.third < cutoffTime) {
                incomingFragments.remove(fragmentID)
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Relay message with adaptive probability (same as iOS)
     */
    private suspend fun relayMessage(packet: BitchatPacket) {
        if (packet.ttl == 0u.toUByte()) return
        
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        
        // Adaptive relay probability based on network size
        val networkSize = activePeers.size
        val relayProb = when {
            networkSize <= 10 -> 1.0
            networkSize <= 30 -> 0.85
            networkSize <= 50 -> 0.7
            networkSize <= 100 -> 0.55
            else -> 0.4
        }
        
        val shouldRelay = relayPacket.ttl >= 4u || networkSize <= 3 || Random.nextDouble() < relayProb
        
        if (shouldRelay) {
            val delay = Random.nextLong(50, 500) // Random delay like iOS
            delay(delay)
            broadcastPacket(relayPacket)
        }
    }
    
    /**
     * Broadcast packet to all connected peers - FIXED to work with both server and client modes
     */
    fun broadcastPacket(packet: BitchatPacket) {
        val data = packet.toBinaryData() ?: return
        
        Log.d(TAG, "Broadcasting packet type ${packet.type} to ${subscribedDevices.size} server connections and ${gattConnections.size} client connections")
        
        // Send to connected devices in server mode (devices connected to our GATT server)
        subscribedDevices.forEach { device ->
            try {
                characteristic?.let { char ->
                    char.value = data
                    val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
                    if (success) {
                        Log.d(TAG, "Sent packet to server connection: ${device.address}")
                    } else {
                        Log.w(TAG, "Failed to send packet to server connection: ${device.address}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            }
        }
        
        // Send to connected devices in client mode (we are connected to their GATT servers)
        gattConnections.forEach { (device, gatt) ->
            try {
                val characteristic = deviceCharacteristics[device]
                if (characteristic != null) {
                    characteristic.value = data
                    val success = gatt.writeCharacteristic(characteristic)
                    if (success) {
                        Log.d(TAG, "Sent packet to client connection: ${device.address}")
                    } else {
                        Log.w(TAG, "Failed to send packet to client connection: ${device.address}")
                    }
                } else {
                    Log.w(TAG, "No characteristic found for client connection: ${device.address}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to client connection ${device.address}: ${e.message}")
            }
        }
    }
    
    /**
     * Send public message
     */
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val message = BitchatMessage(
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = channel
            )
            
            message.toBinaryPayload()?.let { messageData ->
                // Sign the message
                val signature = try {
                    encryptionService.sign(messageData)
                } catch (e: Exception) {
                    null
                }
                
                val packet = BitchatPacket(
                    type = MessageType.MESSAGE.value,
                    senderID = myPeerID.toByteArray(),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = signature,
                    ttl = MAX_TTL
                )
                
                // Add random delay and send
                delay(Random.nextLong(50, 500))
                broadcastPacket(packet)
                
                // Single retry for reliability
                delay(300 + Random.nextLong(0, 200))
                broadcastPacket(packet)
            }
        }
    }
    
    /**
     * Send private message
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty() || recipientNickname.isEmpty()) return
        
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val message = BitchatMessage(
                id = messageID ?: UUID.randomUUID().toString(),
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = myPeerID
            )
            
            message.toBinaryPayload()?.let { messageData ->
                try {
                    // Pad and encrypt
                    val blockSize = MessagePadding.optimalBlockSize(messageData.size)
                    val paddedData = MessagePadding.pad(messageData, blockSize)
                    val encryptedPayload = encryptionService.encrypt(paddedData, recipientPeerID)
                    
                    // Sign
                    val signature = try {
                        encryptionService.sign(encryptedPayload)
                    } catch (e: Exception) {
                        null
                    }
                    
                    val packet = BitchatPacket(
                        type = MessageType.MESSAGE.value,
                        senderID = myPeerID.toByteArray(),
                        recipientID = recipientPeerID.toByteArray(),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encryptedPayload,
                        signature = signature,
                        ttl = MAX_TTL
                    )
                    
                    // Check if recipient is offline and cache for favorites
                    if (!activePeers.containsKey(recipientPeerID)) {
                        val isRecipientFavorite = delegate?.isFavorite(recipientPeerID) ?: false
                        if (isRecipientFavorite) {
                            cacheMessage(packet, messageID ?: message.id)
                        }
                    }
                    
                    // Send with delay
                    delay(Random.nextLong(50, 500))
                    broadcastPacket(packet)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send private message: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Cache message for offline delivery
     */
    private fun cacheMessage(packet: BitchatPacket, messageID: String) {
        // Skip certain message types (same as iOS)
        if (packet.type == MessageType.KEY_EXCHANGE.value ||
            packet.type == MessageType.ANNOUNCE.value ||
            packet.type == MessageType.LEAVE.value) {
            return
        }
        
        // Don't cache broadcast messages
        if (packet.recipientID != null && packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            return
        }
        
        val isForFavorite = packet.recipientID?.let { recipientID ->
            val recipientPeerID = String(recipientID).replace("\u0000", "")
            delegate?.isFavorite(recipientPeerID) ?: false
        } ?: false
        
        val storedMessage = StoredMessage(
            packet = packet,
            timestamp = System.currentTimeMillis(),
            messageID = messageID,
            isForFavorite = isForFavorite
        )
        
        if (isForFavorite && packet.recipientID != null) {
            val recipientPeerID = String(packet.recipientID).replace("\u0000", "")
            if (!favoriteMessageQueue.containsKey(recipientPeerID)) {
                favoriteMessageQueue[recipientPeerID] = mutableListOf()
            }
            favoriteMessageQueue[recipientPeerID]?.add(storedMessage)
            
            // Limit favorite queue size
            if (favoriteMessageQueue[recipientPeerID]?.size ?: 0 > MAX_CACHED_MESSAGES_FAVORITES) {
                favoriteMessageQueue[recipientPeerID]?.removeAt(0)
            }
        } else {
            // Clean up old messages first
            cleanupMessageCache()
            
            messageCache.add(storedMessage)
            
            // Limit cache size
            if (messageCache.size > MAX_CACHED_MESSAGES) {
                messageCache.removeAt(0)
            }
        }
    }
    
    /**
     * Send cached messages to peer
     */
    private fun sendCachedMessages(peerID: String) {
        if (cachedMessagesSentToPeer.contains(peerID)) {
            return // Already sent cached messages to this peer
        }
        
        cachedMessagesSentToPeer.add(peerID)
        
        serviceScope.launch {
            cleanupMessageCache()
            
            val messagesToSend = mutableListOf<StoredMessage>()
            
            // Check favorite queue
            favoriteMessageQueue[peerID]?.let { favoriteMessages ->
                val undeliveredFavorites = favoriteMessages.filter { !deliveredMessages.contains(it.messageID) }
                messagesToSend.addAll(undeliveredFavorites)
                favoriteMessageQueue.remove(peerID)
            }
            
            // Filter regular cached messages for this recipient
            val recipientMessages = messageCache.filter { storedMessage ->
                !deliveredMessages.contains(storedMessage.messageID) &&
                storedMessage.packet.recipientID?.let { recipientID ->
                    String(recipientID).replace("\u0000", "") == peerID
                } == true
            }
            messagesToSend.addAll(recipientMessages)
            
            // Sort by timestamp
            messagesToSend.sortBy { it.timestamp }
            
            if (messagesToSend.isNotEmpty()) {
                Log.d(TAG, "Sending ${messagesToSend.size} cached messages to $peerID")
            }
            
            // Mark as delivered
            val messageIDsToRemove = messagesToSend.map { it.messageID }
            deliveredMessages.addAll(messageIDsToRemove)
            
            // Send with delays
            messagesToSend.forEachIndexed { index, storedMessage ->
                delay(index * 100L) // 100ms between messages
                broadcastPacket(storedMessage.packet)
            }
            
            // Remove sent messages
            messageCache.removeAll { messageIDsToRemove.contains(it.messageID) }
        }
    }
    
    /**
     * Clean up old cached messages
     */
    private fun cleanupMessageCache() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_CACHE_TIMEOUT
        messageCache.removeAll { !it.isForFavorite && it.timestamp < cutoffTime }
        
        // Clean up delivered messages set
        if (deliveredMessages.size > 1000) {
            deliveredMessages.clear()
        }
    }
    
    /**
     * Send delivery acknowledgment
     */
    private fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val ack = DeliveryAck(
                originalMessageID = message.id,
                recipientID = myPeerID,
                recipientNickname = nickname,
                hopCount = (MAX_TTL - 0u).toUByte() // Placeholder hop count
            )
            
            try {
                val ackData = ack.encode() ?: return@launch
                val encryptedPayload = encryptionService.encrypt(ackData, senderPeerID)
                
                val packet = BitchatPacket(
                    type = MessageType.DELIVERY_ACK.value,
                    senderID = myPeerID.toByteArray(),
                    recipientID = senderPeerID.toByteArray(),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = 3u
                )
                
                broadcastPacket(packet)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send delivery ACK: ${e.message}")
            }
        }
    }
    
    /**
     * Send broadcast announce
     */
    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 3u,
                senderID = myPeerID,
                payload = nickname.toByteArray()
            )
            
            // Send multiple times for reliability
            delay(Random.nextLong(0, 500))
            broadcastPacket(announcePacket)
            
            delay(500 + Random.nextLong(0, 500))
            broadcastPacket(announcePacket)
            
            delay(1000 + Random.nextLong(0, 500))
            broadcastPacket(announcePacket)
        }
    }
    
    /**
     * Send announcement to specific peer
     */
    private fun sendAnnouncementToPeer(peerID: String) {
        if (announcedToPeers.contains(peerID)) return
        
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        broadcastPacket(packet)
        announcedToPeers.add(peerID)
    }
    
    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        broadcastPacket(packet)
    }
    
    /**
     * Get peer nicknames
     */
    fun getPeerNicknames(): Map<String, String> = peerNicknames.toMap()
    
    /**
     * Get peer RSSI values
     */
    fun getPeerRSSI(): Map<String, Int> = peerRSSI.toMap()
    
    /**
     * Get debug status information - ADDED for troubleshooting
     */
    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${hasBluetoothPermissions()}")
            appendLine("BLE Scanner Available: ${bleScanner != null}")
            appendLine("BLE Advertiser Available: ${bleAdvertiser != null}")
            appendLine("GATT Server Active: ${gattServer != null}")
            appendLine()
            appendLine("Connected Devices: ${connectedDevices.size}")
            connectedDevices.forEach { (address, device) ->
                appendLine("  - $address")
            }
            appendLine()
            appendLine("GATT Connections: ${gattConnections.size}")
            gattConnections.keys.forEach { device ->
                appendLine("  - ${device.address}")
            }
            appendLine()
            appendLine("Subscribed Devices: ${subscribedDevices.size}")
            subscribedDevices.forEach { device ->
                appendLine("  - ${device.address}")
            }
            appendLine()
            appendLine("Active Peers: ${activePeers.size}")
            activePeers.forEach { (peerID, lastSeen) ->
                val nickname = peerNicknames[peerID] ?: "Unknown"
                val timeSince = (System.currentTimeMillis() - lastSeen) / 1000
                appendLine("  - $peerID ($nickname) - last seen ${timeSince}s ago")
            }
            appendLine()
            appendLine("Peer RSSI Values: ${peerRSSI.size}")
            peerRSSI.forEach { (peerID, rssi) ->
                appendLine("  - $peerID: $rssi dBm")
            }
        }
    }
    
    /**
     * Notify delegate of peer list updates
     */
    private fun notifyPeerListUpdate() {
        val peerList = activePeers.keys.toList().sorted()
        delegate?.didUpdatePeerList(peerList)
    }
    
    /**
     * Start periodic tasks
     */
    private fun startPeriodicTasks() {
        // Cleanup stale peers every minute
        serviceScope.launch {
            while (isActive) {
                delay(60000) // 1 minute
                cleanupStalePeers()
            }
        }
        
        // Reset processed messages every 5 minutes
        serviceScope.launch {
            while (isActive) {
                delay(300000) // 5 minutes
                processedMessages.clear()
                processedKeyExchanges.clear()
            }
        }
    }
    
    /**
     * Clean up stale peers (same 3-minute threshold as iOS)
     */
    private fun cleanupStalePeers() {
        val staleThreshold = 180000L // 3 minutes
        val now = System.currentTimeMillis()
        
        val peersToRemove = activePeers.entries.filter { (_, lastSeen) ->
            now - lastSeen > staleThreshold
        }.map { it.key }
        
        peersToRemove.forEach { peerID ->
            activePeers.remove(peerID)
            peerNicknames[peerID]?.let { nickname ->
                delegate?.didDisconnectFromPeer(nickname)
            }
            peerNicknames.remove(peerID)
            peerRSSI.remove(peerID)
            announcedPeers.remove(peerID)
            announcedToPeers.remove(peerID)
        }
        
        if (peersToRemove.isNotEmpty()) {
            notifyPeerListUpdate()
        }
    }
}

/**
 * Delegate interface for mesh service callbacks
 */
interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didConnectToPeer(peerID: String)
    fun didDisconnectFromPeer(peerID: String)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(ack: DeliveryAck)
    fun didReceiveReadReceipt(receipt: ReadReceipt)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}
