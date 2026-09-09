import ExpoAndroidSmsSenderModule from "./ExpoAndroidSmsSenderModule";
export * from "./ExpoAndroidSmsSender.types";

export async function canSendSms() {
  if (!ExpoAndroidSmsSenderModule || typeof ExpoAndroidSmsSenderModule.canSendSms !== 'function') {
    return { capable: false, reason: 'NOT_SUPPORTED' };
  }
  return await ExpoAndroidSmsSenderModule.canSendSms();
}

export async function getSimCards() {
  if (!ExpoAndroidSmsSenderModule || typeof ExpoAndroidSmsSenderModule.getSimCards !== 'function') {
    return [];
  }
  const serialized = await ExpoAndroidSmsSenderModule.getSimCards();
  return typeof serialized === 'string' ? JSON.parse(serialized) : serialized;
}

export async function sendSms(phoneNumber, text, simCardId, options) {
  if (!ExpoAndroidSmsSenderModule || typeof ExpoAndroidSmsSenderModule.sendSms !== 'function') {
    throw new Error('Native SMS module is not available on this platform.');
  }
  if (options && typeof ExpoAndroidSmsSenderModule.sendSmsWithOptions === 'function') {
    return await ExpoAndroidSmsSenderModule.sendSmsWithOptions(phoneNumber, text, simCardId, options);
  }
  return await ExpoAndroidSmsSenderModule.sendSms(phoneNumber, text, simCardId);
}

export async function sendBulkSms(messages, simCardId, delayMs) {
  if (!ExpoAndroidSmsSenderModule || typeof ExpoAndroidSmsSenderModule.sendBulkSms !== 'function') {
    throw new Error('Native bulk SMS module is not available on this platform.');
  }
  return await ExpoAndroidSmsSenderModule.sendBulkSms(messages, simCardId, delayMs);
}

export default { canSendSms, getSimCards, sendSms, sendBulkSms };