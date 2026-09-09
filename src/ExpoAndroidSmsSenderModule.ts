import { NativeModule, requireNativeModule } from "expo";

import type {
  CanSendSmsResult,
  SendSmsResult,
  BulkSmsRecipient,
  BulkSmsReport,
  SendSmsOptions,
} from "./ExpoAndroidSmsSender.types";

declare class ExpoAndroidSmsSenderModule extends NativeModule {
  canSendSms(): Promise<CanSendSmsResult>;
  getSimCards(): Promise<string>;
  sendSms(
    phoneNumber: string,
    text: string,
    simCardId?: number,
  ): Promise<SendSmsResult>;
  sendSmsWithOptions(
    phoneNumber: string,
    text: string,
    simCardId?: number,
    options?: SendSmsOptions,
  ): Promise<SendSmsResult>;
  sendBulkSms(
    messages: BulkSmsRecipient[],
    simCardId?: number,
    delayMs?: number,
  ): Promise<BulkSmsReport>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoAndroidSmsSenderModule>(
  "ExpoAndroidSmsSender",
);
