/**
 * Represents a detected SIM card on the device.
 */
export type SimCard = {
  /**
   * Unique identifier for the SIM card (subscription ID).
   */
  id: number;

  /**
   * The name assigned to the SIM card by the system, typically
   * corresponding to the carrier name or user-assigned label.
   */
  displayName: string;

  /**
   * The name of the mobile network carrier associated with the SIM.
   */
  carrierName: string;

  /**
   * The slot index where the SIM card is inserted (0-indexed).
   * May be `undefined` if the slot index cannot be determined.
   */
  slotIndex?: number;

  /**
   * Two-letter ISO country code associated with the SIM provider (e.g. 'ke', 'us').
   */
  countryIso?: string | null;

  /**
   * Provisioned MSISDN / phone number of the SIM card, if available from carrier/SIM.
   */
  phoneNumber?: string | null;

  /**
   * Whether this SIM is currently configured as the Android system default for sending SMS.
   */
  isDefaultSms?: boolean;

  /**
   * Whether this SIM is currently configured as the Android system default for cellular data.
   */
  isDefaultData?: boolean;
};

/**
 * Result of the device SMS capability check.
 */
export type CanSendSmsResult = {
  /**
   * `true` if the device has telephony hardware, is not in Airplane Mode,
   * has SMS permissions granted, and has a ready SIM card.
   */
  capable: boolean;

  /**
   * Reason explaining why SMS dispatch is not capable, or `null` if capable.
   * Examples: 'NO_TELEPHONY_HARDWARE', 'AIRPLANE_MODE_ENABLED', 'PERMISSION_DENIED', 'SIM_NOT_READY'.
   */
  reason?: string | null;
};

/**
 * Configuration options for sending an individual SMS.
 */
export type SendSmsOptions = {
  /**
   * Maximum time in milliseconds to wait for modem dispatch response before timing out.
   * Defaults to 25000ms.
   */
  timeoutMs?: number;

  /**
   * Whether to wait for carrier delivery receipt before resolving.
   * Defaults to false (resolves once handed off to local tower).
   */
  requireDelivery?: boolean;
};

/**
 * Result of an individual SMS transmission.
 */
export type SendSmsResult = {
  /**
   * Whether the SMS was successfully accepted and dispatched by the cellular modem.
   */
  success: boolean;

  /**
   * Whether delivery confirmation was received from the recipient's carrier (if requested).
   */
  delivered?: boolean;
};

/**
 * Recipient message payload for bulk SMS operations.
 */
export type BulkSmsRecipient = {
  /**
   * Optional unique identifier for tracking this message item in the results.
   */
  id?: string;

  /**
   * Recipient phone number in international (E.164) or standard format.
   */
  to?: string;

  /**
   * Alias for `to` (recipient phone number).
   */
  phoneNumber?: string;

  /**
   * Message body to be transmitted.
   */
  message: string;
};

/**
 * Execution outcome for an individual message item within a bulk batch.
 */
export type BulkSmsResultItem = {
  /**
   * Unique identifier of the message item.
   */
  id: string;

  /**
   * Recipient phone number that was targeted.
   */
  to: string;

  /**
   * Whether this individual message was dispatched successfully.
   */
  success: boolean;

  /**
   * Whether delivery confirmation was received (if delivery reporting was active).
   */
  delivered?: boolean;

  /**
   * Error message explaining why dispatch failed, including 3GPP carrier radio codes.
   */
  error?: string;
};

/**
 * Summary report generated upon completion of a bulk SMS dispatch run.
 */
export type BulkSmsReport = {
  /**
   * Total number of message items processed in this batch.
   */
  total: number;

  /**
   * Count of messages successfully accepted by the cellular radio.
   */
  successCount: number;

  /**
   * Count of messages that failed dispatch.
   */
  failedCount: number;

  /**
   * Detailed per-item execution results.
   */
  results: BulkSmsResultItem[];
};