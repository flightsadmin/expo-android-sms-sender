import { Platform } from 'react-native';

/**
 * Universal Native Module Loader.
 * Supports any Expo SDK version (SDK 48 to SDK 54+) and Bare React Native:
 * 1. Tries `expo.requireNativeModule` (Modern Expo SDK 51+)
 * 2. Falls back to `expo-modules-core.requireNativeModule` (Legacy Expo SDK 48-50)
 * 3. Falls back to `NativeModules.ExpoAndroidSmsSender` (Bare React Native)
 * 4. Gracefully resolves null on non-Android platforms (iOS / Web)
 */
let nativeModule = null;

if (Platform.OS === 'android') {
  try {
    // Modern Expo SDK (SDK 51, 52, 53, 54+)
    const expo = require('expo');
    if (typeof expo.requireNativeModule === 'function') {
      nativeModule = expo.requireNativeModule('ExpoAndroidSmsSender');
    }
  } catch (_) {}

  if (!nativeModule) {
    try {
      // Legacy Expo SDK (SDK 48 - 50)
      const core = require('expo-modules-core');
      if (typeof core.requireNativeModule === 'function') {
        nativeModule = core.requireNativeModule('ExpoAndroidSmsSender');
      }
    } catch (_) {}
  }

  if (!nativeModule) {
    try {
      // Direct React Native NativeModules fallback
      const { NativeModules } = require('react-native');
      nativeModule = NativeModules.ExpoAndroidSmsSender;
    } catch (_) {}
  }
}

export default nativeModule;