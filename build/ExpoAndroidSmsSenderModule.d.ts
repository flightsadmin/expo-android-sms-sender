import { NativeModule } from 'expo';
declare class ExpoAndroidSmsSenderModule extends NativeModule {
    getSimCards(): Promise<string>;
    sendSms(phoneNumber: string, text: string, simCardId?: number): Promise<void>;
}
declare const _default: ExpoAndroidSmsSenderModule;
export default _default;
//# sourceMappingURL=ExpoAndroidSmsSenderModule.d.ts.map