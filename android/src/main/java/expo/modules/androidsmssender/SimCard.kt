package expo.modules.androidsmssender

/**
 * Enriched SIM Card Data Model.
 * Represents hardware slot, carrier identity, network ISO country code,
 * provisioned phone number, and default SIM status for cellular calls and SMS.
 */
data class SimCard(
  val id: Int,
  val displayName: String,
  val carrierName: String,
  val slotIndex: Int?,
  val countryIso: String? = null,
  val phoneNumber: String? = null,
  val isDefaultSms: Boolean = false,
  val isDefaultData: Boolean = false
)
