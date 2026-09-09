package expo.modules.androidsmssender

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ExpoAndroidSmsSenderModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoAndroidSmsSender")

    /**
     * Hardware & Airplane Mode Pre-flight Check.
     * Evaluates telephony radio existence, Airplane Mode status, runtime permissions,
     * and SIM readiness before attempting any SMS dispatch.
     */
    AsyncFunction("canSendSms") { promise: Promise ->
      val context = appContext.reactContext ?: run {
        promise.resolve(mapOf("capable" to false, "reason" to "NO_CONTEXT"))
        return@AsyncFunction
      }

      // Check if the device has cellular telephony hardware (e.g. Wi-Fi-only tablets return false)
      val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
      if (!hasTelephony) {
        promise.resolve(mapOf("capable" to false, "reason" to "NO_TELEPHONY_HARDWARE"))
        return@AsyncFunction
      }

      // Check if Airplane Mode is currently enabled
      val isAirplaneMode = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
      } catch (_: Exception) {
        false
      }
      if (isAirplaneMode) {
        promise.resolve(mapOf("capable" to false, "reason" to "AIRPLANE_MODE_ENABLED"))
        return@AsyncFunction
      }

      // Check if SEND_SMS permission is granted
      val hasSendPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.SEND_SMS
      ) == PackageManager.PERMISSION_GRANTED
      if (!hasSendPermission) {
        promise.resolve(mapOf("capable" to false, "reason" to "PERMISSION_DENIED"))
        return@AsyncFunction
      }

      // Check SIM card readiness
      val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
      val simReady = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
      if (!simReady) {
        promise.resolve(mapOf("capable" to false, "reason" to "SIM_NOT_READY"))
        return@AsyncFunction
      }

      promise.resolve(mapOf("capable" to true, "reason" to null))
    }

    /**
     * Enriched SIM Card Discovery with Inactive Slot Filtering.
     * 1. Filters out ghost slots and inactive SIM cards (e.g. "No service", "Emergency calls only").
     * 2. Checks hardware readiness via TelephonyManager.getSimState(slot).
     * 3. Extracts countryIso, provisioned phoneNumber, and system default SMS/data indicators.
     */
    AsyncFunction("getSimCards") { promise: Promise ->
      val context = appContext.reactContext ?: return@AsyncFunction

      if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        promise.reject(PERMISSION_DENIED, "Permission not granted to access SIM card info.", null)
        return@AsyncFunction
      }

      try {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList

        // Detect which SIM is configured as the Android system default for SMS
        val defaultSmsSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
          } catch (_: Exception) {
            -1
          }
        } else {
          -1
        }

        // Detect which SIM is configured as the Android system default for cellular data
        val defaultDataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          try {
            SubscriptionManager.getDefaultDataSubscriptionId()
          } catch (_: Exception) {
            -1
          }
        } else {
          -1
        }

        val inactiveTerms = listOf("no service", "emergency", "no sim", "disabled", "inactive")

        val simCards = activeSubscriptions?.filter { info ->
          val slot = info.simSlotIndex
          val carrier = info.carrierName?.toString()?.trim()?.lowercase() ?: ""
          val display = info.displayName?.toString()?.trim()?.lowercase() ?: ""
          val isInactiveText = inactiveTerms.any { carrier.contains(it) || display.contains(it) }

          // Exclude invalid SIM slot indices or empty/inactive status descriptions
          if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX || isInactiveText) {
            false
          } else {
            // Verify hardware slot readiness via public TelephonyManager API
            val isReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              try {
                telephonyManager.getSimState(slot) == TelephonyManager.SIM_STATE_READY
              } catch (_: Exception) {
                true
              }
            } else {
              telephonyManager.simState == TelephonyManager.SIM_STATE_READY
            }
            isReady
          }
        }?.map { info ->
          val countryIso = info.countryIso?.trim()?.lowercase()?.ifBlank { null }
          val phoneNum = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              try {
                subscriptionManager.getPhoneNumber(info.subscriptionId)?.trim()?.ifBlank { null }
              } catch (_: Exception) {
                info.number?.trim()?.ifBlank { null }
              }
            } else {
              info.number?.trim()?.ifBlank { null }
            }
          } catch (_: Exception) {
            null
          }

          SimCard(
            id = info.subscriptionId,
            displayName = info.displayName.toString(),
            carrierName = info.carrierName.toString(),
            slotIndex = if (info.simSlotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) null else info.simSlotIndex,
            countryIso = countryIso,
            phoneNumber = phoneNum,
            isDefaultSms = (defaultSmsSubId != -1 && info.subscriptionId == defaultSmsSubId),
            isDefaultData = (defaultDataSubId != -1 && info.subscriptionId == defaultDataSubId)
          )
        } ?: emptyList()

        promise.resolve(Gson().toJson(simCards))
      } catch (e: Exception) {
        promise.reject(GENERIC_ERROR, "Failed to retrieve SIM card info: ${e.message}", e)
      }
    }

    /**
     * Standard Send SMS Function with Background Dispatch.
     * Dispatches SMS on a background worker thread so the main/UI thread never stalls.
     */
    AsyncFunction("sendSms") { phoneNumber: String, message: String, simCardId: Int?, promise: Promise ->
      val context = appContext.reactContext ?: return@AsyncFunction

      if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        promise.reject(PERMISSION_DENIED, "Permission not granted to send SMS.", null)
        return@AsyncFunction
      }

      Thread {
        val result = executeSmsDispatch(context, phoneNumber, message, simCardId)
        if (result.success) {
          promise.resolve(mapOf("success" to true, "delivered" to result.delivered))
        } else {
          promise.reject(result.errorCode ?: GENERIC_ERROR, result.errorMessage ?: "Failed to dispatch SMS", null)
        }
      }.start()
    }

    /**
     * Advanced Send SMS with Configurable Timeout & Delivery Report Options.
     * Supports options: { timeoutMs?: number, requireDelivery?: boolean }
     */
    AsyncFunction("sendSmsWithOptions") { phoneNumber: String, message: String, simCardId: Int?, options: Map<String, Any?>?, promise: Promise ->
      val context = appContext.reactContext ?: return@AsyncFunction

      if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        promise.reject(PERMISSION_DENIED, "Permission not granted to send SMS.", null)
        return@AsyncFunction
      }

      val timeoutMs = (options?.get("timeoutMs") as? Number)?.toLong() ?: 25000L
      val requireDelivery = (options?.get("requireDelivery") as? Boolean) ?: false

      Thread {
        val result = executeSmsDispatch(context, phoneNumber, message, simCardId, timeoutMs, requireDelivery)
        if (result.success) {
          promise.resolve(mapOf("success" to true, "delivered" to result.delivered))
        } else {
          promise.reject(result.errorCode ?: GENERIC_ERROR, result.errorMessage ?: "Failed to dispatch SMS", null)
        }
      }.start()
    }

    /**
     * Native Sequential Bulk SMS Dispatch.
     * Iterates through an array of messages natively with configurable delay,
     * avoiding bridge overhead and returning a full execution report.
     */
    AsyncFunction("sendBulkSms") { messages: List<Map<String, Any>>, simCardId: Int?, delayMs: Long?, promise: Promise ->
      val context = appContext.reactContext ?: return@AsyncFunction

      if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        promise.reject(PERMISSION_DENIED, "Permission not granted to send SMS.", null)
        return@AsyncFunction
      }

      val delay = delayMs ?: 2000L

      Thread {
        val results = ArrayList<Map<String, Any?>>()
        var successCount = 0
        var failedCount = 0

        for (item in messages) {
          val phone = item["to"] as? String ?: item["phoneNumber"] as? String ?: ""
          val msg = item["message"] as? String ?: ""
          val itemId = item["id"] as? String ?: phone

          if (phone.isBlank() || msg.isBlank()) {
            results.add(mapOf(
              "id" to itemId,
              "to" to phone,
              "success" to false,
              "error" to "Empty phone number or message"
            ))
            failedCount++
            continue
          }

          val res = executeSmsDispatch(context, phone, msg, simCardId)
          if (res.success) {
            results.add(mapOf(
              "id" to itemId,
              "to" to phone,
              "success" to true,
              "delivered" to res.delivered
            ))
            successCount++
          } else {
            results.add(mapOf(
              "id" to itemId,
              "to" to phone,
              "success" to false,
              "error" to (res.errorMessage ?: "Failed to dispatch")
            ))
            failedCount++
          }

          if (delay > 0) {
            try {
              Thread.sleep(delay)
            } catch (_: InterruptedException) {}
          }
        }

        promise.resolve(mapOf(
          "total" to messages.size,
          "successCount" to successCount,
          "failedCount" to failedCount,
          "results" to results
        ))
      }.start()
    }
  }

  private data class SmsResult(
    val success: Boolean,
    val delivered: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
  )

  /**
   * Core Native SMS Dispatch Engine.
   * Features:
   * 1. Multi-part SMS splitting via divideMessage to prevent UCS-2 / GSM length failures.
   * 2. FLAG_MUTABLE on Android 12+ so the telephony system can write back result extras.
   * 3. RECEIVER_EXPORTED on Android 14+ so external system telephony broadcasts are received.
   * 4. CountDownLatch watchdog timer so the promise never hangs if signal is lost.
   * 5. Detailed 3GPP radio error translation (Cause 21 = Short of funds / airtime, etc.).
   * 6. Guaranteed receiver unregistration in a finally block to prevent memory leaks.
   */
  private fun executeSmsDispatch(
    context: Context,
    phoneNumber: String,
    message: String,
    simCardId: Int?,
    timeoutMs: Long = 25000L,
    requireDelivery: Boolean = false
  ): SmsResult {
    val smsManager = getSmsManager(context, simCardId)
    // Divide message to avoid 160-char GSM / 70-char UCS-2 single-message limit crashes
    val parts = try {
      smsManager.divideMessage(message)
    } catch (e: Exception) {
      return SmsResult(success = false, errorCode = "INVALID_ARGUMENTS", errorMessage = e.message)
    }

    val partCount = parts.size
    val actionId = "${System.currentTimeMillis()}_${(0..99999).random()}"
    val sentAction = "${SMS_SENT_ACTION}_$actionId"
    val deliveredAction = "${SMS_DELIVERY_ACTION}_$actionId"

    // Android 12+ (API 31) requires FLAG_MUTABLE so the telephony service can attach result extras
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }

    val sentIntents = ArrayList<PendingIntent>()
    val deliveryIntents = if (requireDelivery) ArrayList<PendingIntent>() else null

    for (i in 0 until partCount) {
      val sIntent = Intent(sentAction).setPackage(context.packageName)
      sIntent.putExtra("partIndex", i)
      val piSent = PendingIntent.getBroadcast(
        context,
        (System.currentTimeMillis() and 0xfffffff).toInt() + i,
        sIntent,
        flags
      )
      sentIntents.add(piSent)

      if (requireDelivery) {
        val dIntent = Intent(deliveredAction).setPackage(context.packageName)
        dIntent.putExtra("partIndex", i)
        val piDelivered = PendingIntent.getBroadcast(
          context,
          (System.currentTimeMillis() and 0xfffffff).toInt() + 1000 + i,
          dIntent,
          flags
        )
        deliveryIntents?.add(piDelivered)
      }
    }

    // Android 14+ (API 34) requires RECEIVER_EXPORTED for broadcasts from the telephony process
    val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.RECEIVER_EXPORTED
    } else {
      0
    }

    val remainingSent = AtomicInteger(partCount)
    val remainingDelivered = AtomicInteger(if (requireDelivery) partCount else 0)
    val isFailed = AtomicBoolean(false)
    val isCompleted = AtomicBoolean(false)

    var failureCode: String? = null
    var failureMessage: String? = null

    // Latch to synchronize background thread and enforce watchdog timeout
    val latch = CountDownLatch(1)

    var sentReceiver: BroadcastReceiver? = null
    var deliveryReceiver: BroadcastReceiver? = null

    sentReceiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        val code = resultCode
        // Extract raw 3GPP cellular baseband radio cause code from the telephony intent
        val radioErrorCode = intent?.getIntExtra("errorCode", -1) ?: -1

        if (code != Activity.RESULT_OK) {
          if (isFailed.compareAndSet(false, true)) {
            val errCode = SMS_ERROR_CODES[code] ?: "UNKNOWN_ERROR"
            var errMsg = SMS_ERROR_DESCRIPTIONS[errCode] ?: "Error code: $code"
            // Translate raw radio code to human-friendly reason (e.g. Short of funds / balance)
            if (radioErrorCode != -1) {
              val radioDesc = RADIO_ERROR_DESCRIPTIONS[radioErrorCode]
              if (radioDesc != null) {
                errMsg = "$radioDesc (Carrier code: $radioErrorCode)"
              } else {
                errMsg += " (Radio error code: $radioErrorCode)"
              }
            }
            failureCode = errCode
            failureMessage = errMsg
            latch.countDown()
          }
        } else {
          if (remainingSent.decrementAndGet() <= 0) {
            if (!requireDelivery) {
              isCompleted.set(true)
              latch.countDown()
            }
          }
        }
      }
    }

    if (requireDelivery) {
      deliveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          if (resultCode == Activity.RESULT_OK) {
            if (remainingDelivered.decrementAndGet() <= 0) {
              isCompleted.set(true)
              latch.countDown()
            }
          } else {
            if (isFailed.compareAndSet(false, true)) {
              failureCode = "DELIVERY_FAILED"
              failureMessage = "Recipient carrier rejected message delivery"
              latch.countDown()
            }
          }
        }
      }
      ContextCompat.registerReceiver(context, deliveryReceiver, IntentFilter(deliveredAction), receiverFlags)
    }

    ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(sentAction), receiverFlags)

    try {
      if (partCount > 1) {
        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveryIntents)
      } else {
        smsManager.sendTextMessage(phoneNumber, null, message, sentIntents[0], deliveryIntents?.get(0))
      }

      // Wait for modem response up to timeoutMs (default 25s) to guarantee no infinite hanging
      val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      if (!done) {
        return SmsResult(
          success = false,
          errorCode = "TIMEOUT",
          errorMessage = "SMS dispatch timed out after ${timeoutMs / 1000}s without cellular modem response"
        )
      }

      if (isFailed.get()) {
        return SmsResult(
          success = false,
          errorCode = failureCode ?: "FAILED",
          errorMessage = failureMessage ?: "Failed to dispatch SMS"
        )
      }

      return SmsResult(
        success = true,
        delivered = if (requireDelivery) isCompleted.get() else false
      )
    } catch (e: IllegalArgumentException) {
      return SmsResult(success = false, errorCode = "INVALID_ARGUMENTS", errorMessage = e.message)
    } catch (e: UnsupportedOperationException) {
      return SmsResult(success = false, errorCode = "NOT_SUPPORTED", errorMessage = e.message)
    } catch (e: Exception) {
      return SmsResult(success = false, errorCode = GENERIC_ERROR, errorMessage = e.message)
    } finally {
      // Guaranteed unregistration to prevent broadcast receiver leaks
      try {
        sentReceiver?.let { context.unregisterReceiver(it) }
      } catch (_: Exception) {}
      try {
        deliveryReceiver?.let { context.unregisterReceiver(it) }
      } catch (_: Exception) {}
    }
  }

  /**
   * SmsManager Subscription Resolution.
   * Uses modern API 31 context.getSystemService(SmsManager::class.java).createForSubscriptionId()
   * on Android 12+, with safe fallback to legacy getSmsManagerForSubscriptionId and getDefault().
   */
  private fun getSmsManager(context: Context, simCardId: Int?): SmsManager {
    if (simCardId == null) {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
          context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } catch (_: Exception) {
          SmsManager.getDefault()
        }
      } else {
        SmsManager.getDefault()
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        val baseManager = context.getSystemService(SmsManager::class.java)
        if (baseManager != null) {
          return baseManager.createForSubscriptionId(simCardId)
        }
      } catch (_: Exception) {}
    }

    return try {
      SmsManager.getSmsManagerForSubscriptionId(simCardId)
    } catch (_: Exception) {
      SmsManager.getDefault()
    }
  }

  companion object {
    private const val PERMISSION_DENIED = "PERMISSION_DENIED"
    private const val GENERIC_ERROR = "ERROR"
    private const val SMS_SENT_ACTION = "SMS_SENT"
    private const val SMS_DELIVERY_ACTION = "SMS_DELIVERED"
    
    private val SMS_ERROR_CODES = mapOf(
      SmsManager.RESULT_ERROR_GENERIC_FAILURE to "GENERIC_FAILURE",
      SmsManager.RESULT_ERROR_NO_SERVICE to "NO_SERVICE",
      SmsManager.RESULT_ERROR_NULL_PDU to "NULL_PDU",
      SmsManager.RESULT_ERROR_RADIO_OFF to "RADIO_OFF",
      SmsManager.RESULT_ERROR_LIMIT_EXCEEDED to "LIMIT_EXCEEDED",
      SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE to "FDN_CHECK_FAILURE",
      SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED to "SHORT_CODE_NOT_ALLOWED",
      SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED to "SHORT_CODE_NEVER_ALLOWED",
      SmsManager.RESULT_RADIO_NOT_AVAILABLE to "RADIO_NOT_AVAILABLE",
      SmsManager.RESULT_NETWORK_REJECT to "NETWORK_REJECT",
      SmsManager.RESULT_INVALID_ARGUMENTS to "INVALID_ARGUMENTS",
      SmsManager.RESULT_INVALID_STATE to "INVALID_STATE",
      SmsManager.RESULT_NO_MEMORY to "NO_MEMORY",
      SmsManager.RESULT_INVALID_SMS_FORMAT to "INVALID_SMS_FORMAT",
      SmsManager.RESULT_SYSTEM_ERROR to "SYSTEM_ERROR",
      SmsManager.RESULT_MODEM_ERROR to "MODEM_ERROR",
      SmsManager.RESULT_NETWORK_ERROR to "NETWORK_ERROR",
      SmsManager.RESULT_ENCODING_ERROR to "ENCODING_ERROR",
      SmsManager.RESULT_INVALID_SMSC_ADDRESS to "INVALID_SMSC_ADDRESS",
      SmsManager.RESULT_OPERATION_NOT_ALLOWED to "OPERATION_NOT_ALLOWED",
      SmsManager.RESULT_INTERNAL_ERROR to "INTERNAL_ERROR",
      SmsManager.RESULT_NO_RESOURCES to "NO_RESOURCES",
      SmsManager.RESULT_CANCELLED to "CANCELLED",
      SmsManager.RESULT_REQUEST_NOT_SUPPORTED to "REQUEST_NOT_SUPPORTED",
      SmsManager.RESULT_NO_BLUETOOTH_SERVICE to "NO_BLUETOOTH_SERVICE",
      SmsManager.RESULT_INVALID_BLUETOOTH_ADDRESS to "INVALID_BLUETOOTH_ADDRESS",
      SmsManager.RESULT_BLUETOOTH_DISCONNECTED to "BLUETOOTH_DISCONNECTED",
      SmsManager.RESULT_UNEXPECTED_EVENT_STOP_SENDING to "UNEXPECTED_EVENT_STOP_SENDING",
      SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY to "SMS_BLOCKED_DURING_EMERGENCY",
      SmsManager.RESULT_SMS_SEND_RETRY_FAILED to "SMS_SEND_RETRY_FAILED",
      SmsManager.RESULT_REMOTE_EXCEPTION to "REMOTE_EXCEPTION",
      SmsManager.RESULT_NO_DEFAULT_SMS_APP to "NO_DEFAULT_SMS_APP",
      SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE to "RIL_RADIO_NOT_AVAILABLE",
      SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY to "RIL_SMS_SEND_FAIL_RETRY",
      SmsManager.RESULT_RIL_NETWORK_REJECT to "RIL_NETWORK_REJECT",
      SmsManager.RESULT_RIL_INVALID_STATE to "RIL_INVALID_STATE",
      SmsManager.RESULT_RIL_INVALID_ARGUMENTS to "RIL_INVALID_ARGUMENTS",
      SmsManager.RESULT_RIL_NO_MEMORY to "RIL_NO_MEMORY",
      SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED to "RIL_REQUEST_RATE_LIMITED",
      SmsManager.RESULT_RIL_INVALID_SMS_FORMAT to "RIL_INVALID_SMS_FORMAT",
      SmsManager.RESULT_RIL_SYSTEM_ERR to "RIL_SYSTEM_ERR",
      SmsManager.RESULT_RIL_ENCODING_ERR to "RIL_ENCODING_ERR",
      SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS to "RIL_INVALID_SMSC_ADDRESS",
      SmsManager.RESULT_RIL_MODEM_ERR to "RIL_MODEM_ERR",
      SmsManager.RESULT_RIL_NETWORK_ERR to "RIL_NETWORK_ERR",
      SmsManager.RESULT_RIL_INTERNAL_ERR to "RIL_INTERNAL_ERR",
      SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED to "RIL_REQUEST_NOT_SUPPORTED",
      SmsManager.RESULT_RIL_INVALID_MODEM_STATE to "RIL_INVALID_MODEM_STATE",
      SmsManager.RESULT_RIL_NETWORK_NOT_READY to "RIL_NETWORK_NOT_READY",
      SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED to "RIL_OPERATION_NOT_ALLOWED",
      SmsManager.RESULT_RIL_NO_RESOURCES to "RIL_NO_RESOURCES",
      SmsManager.RESULT_RIL_CANCELLED to "RIL_CANCELLED",
      SmsManager.RESULT_RIL_SIM_ABSENT to "RIL_SIM_ABSENT",
      SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED to "RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED",
      SmsManager.RESULT_RIL_ACCESS_BARRED to "RIL_ACCESS_BARRED",
      SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL to "RIL_BLOCKED_DUE_TO_CALL",
      "DELIVERY_FAILED" to "Message delivery failed",
      "TIMEOUT" to "Modem dispatch timed out",
      "UNKNOWN_ERROR" to "Unknown error occurred"
    )

    private val SMS_ERROR_DESCRIPTIONS = mapOf(
      "GENERIC_FAILURE" to "Generic failure",
      "NO_SERVICE" to "No service available",
      "NULL_PDU" to "Null PDU",
      "RADIO_OFF" to "Radio off",
      "LIMIT_EXCEEDED" to "SMS sending limit exceeded",
      "FDN_CHECK_FAILURE" to "Fixed dialing number (FDN) check failed",
      "SHORT_CODE_NOT_ALLOWED" to "Short code not allowed",
      "SHORT_CODE_NEVER_ALLOWED" to "Short code never allowed",
      "RADIO_NOT_AVAILABLE" to "Radio not available",
      "NETWORK_REJECT" to "Network rejected the SMS",
      "INVALID_ARGUMENTS" to "Invalid arguments provided",
      "INVALID_STATE" to "Invalid state",
      "NO_MEMORY" to "No memory available",
      "INVALID_SMS_FORMAT" to "Invalid SMS format",
      "SYSTEM_ERROR" to "System error",
      "MODEM_ERROR" to "Modem error",
      "NETWORK_ERROR" to "Network error",
      "ENCODING_ERROR" to "Encoding error",
      "INVALID_SMSC_ADDRESS" to "Invalid SMSC address",
      "OPERATION_NOT_ALLOWED" to "Operation not allowed",
      "INTERNAL_ERROR" to "Internal error",
      "NO_RESOURCES" to "No resources available",
      "CANCELLED" to "SMS sending cancelled",
      "REQUEST_NOT_SUPPORTED" to "Request not supported",
      "NO_BLUETOOTH_SERVICE" to "No Bluetooth service available",
      "INVALID_BLUETOOTH_ADDRESS" to "Invalid Bluetooth address",
      "BLUETOOTH_DISCONNECTED" to "Bluetooth disconnected",
      "UNEXPECTED_EVENT_STOP_SENDING" to "Unexpected event stopped SMS sending",
      "SMS_BLOCKED_DURING_EMERGENCY" to "SMS blocked during emergency",
      "SMS_SEND_RETRY_FAILED" to "SMS send retry failed",
      "REMOTE_EXCEPTION" to "Remote exception occurred",
      "NO_DEFAULT_SMS_APP" to "No default SMS app",
      "RIL_RADIO_NOT_AVAILABLE" to "RIL radio not available",
      "RIL_SMS_SEND_FAIL_RETRY" to "RIL SMS send failed, retry",
      "RIL_NETWORK_REJECT" to "RIL network rejected the SMS",
      "RIL_INVALID_STATE" to "RIL invalid state",
      "RIL_INVALID_ARGUMENTS" to "RIL invalid arguments",
      "RIL_NO_MEMORY" to "RIL no memory available",
      "RIL_REQUEST_RATE_LIMITED" to "RIL request rate limited",
      "RIL_INVALID_SMS_FORMAT" to "RIL invalid SMS format",
      "RIL_SYSTEM_ERR" to "RIL system error",
      "RIL_ENCODING_ERR" to "RIL encoding error",
      "RIL_INVALID_SMSC_ADDRESS" to "RIL invalid SMSC address",
      "RIL_MODEM_ERR" to "RIL modem error",
      "RIL_NETWORK_ERR" to "RIL network error",
      "RIL_INTERNAL_ERR" to "RIL internal error",
      "RIL_REQUEST_NOT_SUPPORTED" to "RIL request not supported",
      "RIL_INVALID_MODEM_STATE" to "RIL invalid modem state",
      "RIL_NETWORK_NOT_READY" to "RIL network not ready",
      "RIL_OPERATION_NOT_ALLOWED" to "RIL operation not allowed",
      "RIL_NO_RESOURCES" to "RIL no resources available",
      "RIL_CANCELLED" to "RIL SMS sending cancelled",
      "RIL_SIM_ABSENT" to "RIL SIM absent",
      "RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED" to "RIL simultaneous SMS and call not allowed",
      "RIL_ACCESS_BARRED" to "RIL access barred",
      "RIL_BLOCKED_DUE_TO_CALL" to "RIL SMS blocked due to call",
      "DELIVERY_FAILED" to "Message delivery failed",
      "TIMEOUT" to "Cellular modem dispatch timed out",
      "UNKNOWN_ERROR" to "Unknown error occurred"
    )

    /**
     * 3GPP GSM Radio Modem Cause Code Descriptions.
     * Translates raw baseband radio cause codes into descriptive user-facing explanations.
     * Specifically identifies airtime depletion (Cause 21, 10, 50) and number invalidity.
     */
    private val RADIO_ERROR_DESCRIPTIONS = mapOf(
      1 to "Unallocated / non-existent number",
      10 to "Call barred by operator (Check airtime balance / bundle)",
      21 to "Short of funds / Insufficient airtime balance",
      27 to "Destination out of order",
      28 to "Unknown subscriber",
      29 to "Facility rejected",
      30 to "Unknown subscriber",
      38 to "Network out of order",
      41 to "Temporary carrier failure",
      42 to "Carrier network congestion",
      47 to "Resource unavailable",
      50 to "Facility rejected / Insufficient airtime funds",
      69 to "Requested facility not implemented",
      111 to "Protocol error",
      127 to "Interworking error"
    )
  }
}
