import {
  BulkSmsRecipient,
  BulkSmsReport,
  CanSendSmsResult,
  SendSmsOptions,
  SendSmsResult,
  SimCard,
} from "./ExpoAndroidSmsSender.types";

export * from "./ExpoAndroidSmsSender.types";

/**
 * Checks whether the device is capable of sending SMS messages.
 *
 * Verifies that the device has telephony radio hardware, that Airplane Mode is off,
 * that SMS permissions are granted, and that an active SIM card is ready.
 *
 * @returns A promise that resolves to a {@link CanSendSmsResult} object indicating capability and reason if unavailable.
 */
export declare function canSendSms(): Promise<CanSendSmsResult>;

/**
 * Retrieves a list of available SIM cards on the device.
 *
 * This function queries the device for active SIM cards and returns their details,
 * automatically filtering out inactive slots and providing carrier, country ISO,
 * and system default SMS preferences.
 * It requires the `READ_PHONE_STATE` permission to access SIM card information.
 * If the permission is not granted, the function will reject with an error.
 *
 * @returns A promise that resolves to an array of {@link SimCard} objects.
 *
 * @throws {Error} If permission is denied or there is a failure in retrieving SIM card info.
 */
export declare function getSimCards(): Promise<SimCard[]>;

/**
 * Sends an SMS message to a specified phone number.
 *
 * This function attempts to send an SMS using the system's SMS manager. It requires
 * the `SEND_SMS` permission to be granted by the user. If permission is not granted,
 * the function will reject with a `PERMISSION_DENIED` error.
 *
 * If `simCardId` is provided, the SMS will be sent using the specified SIM card.
 * If omitted, the system's default SIM card will be used. On devices with multiple SIMs,
 * this may prompt the user to choose a SIM if no default is set.
 *
 * ## Error Handling
 * - `PERMISSION_DENIED`: The required permissions were not granted.
 * - `INVALID_ARGUMENTS`: The phone number or message text is invalid.
 * - `NOT_SUPPORTED`: The device does not support sending SMS.
 * - Various system and carrier-related errors as specified in {@link https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.app.PendingIntent) Android documentation}.
 *
 * @param phoneNumber The recipient's phone number.
 * @param text The message body to be sent.
 * @param simCardId (Optional) The ID of the SIM card to use, as retrieved by {@link getSimCards}.
 * @param options (Optional) Additional options such as timeoutMs and requireDelivery.
 *
 * @returns A promise that resolves when the message is successfully sent.
 *          If the operation fails, the promise is rejected with an error.
 *
 * @throws {Error} If sending the SMS fails due to permissions, invalid input, or system errors.
 */
export declare function sendSms(
  phoneNumber: string,
  text: string,
  simCardId?: number,
  options?: SendSmsOptions
): Promise<SendSmsResult>;

/**
 * Sends a list of SMS messages sequentially in a native background loop.
 *
 * Iterates through the provided list of recipient messages on a background thread
 * with an optional delay between dispatches to comply with carrier rate limits.
 *
 * @param messages An array of {@link BulkSmsRecipient} objects containing recipient numbers and messages.
 * @param simCardId (Optional) The ID of the SIM card to use.
 * @param delayMs (Optional) Milliseconds to wait between message dispatches (defaults to 2000ms).
 *
 * @returns A promise that resolves to a {@link BulkSmsReport} summary of sent and failed messages.
 */
export declare function sendBulkSms(
  messages: BulkSmsRecipient[],
  simCardId?: number,
  delayMs?: number
): Promise<BulkSmsReport>;

declare const _default: {
  canSendSms: typeof canSendSms;
  getSimCards: typeof getSimCards;
  sendSms: typeof sendSms;
  sendBulkSms: typeof sendBulkSms;
};
export default _default;