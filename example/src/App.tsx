import React, { useState } from "react";
import {
  PermissionsAndroid,
  SafeAreaView,
  Text,
  TouchableOpacity,
  View,
  TextInput,
  Alert,
  Permission,
  StyleSheet,
  ScrollView,
  Platform,
  ActivityIndicator,
} from "react-native";
import ExpoAndroidSmsSender, {
  SimCard,
  canSendSms,
  getSimCards,
  sendSms,
} from "expo-android-sms-sender";

/** Requests the specified Android permission. */
const requestPermission = async (permission: Permission) => {
  if (Platform.OS !== "android") {
    return true;
  }
  try {
    const granted = await PermissionsAndroid.request(permission);
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  } catch (err) {
    console.warn(`Error requesting permission ${permission}:`, err);
    return false;
  }
};

export default function App() {
  const [phoneNumber, setPhoneNumber] = useState("");
  const [message, setMessage] = useState("Hello from Expo 57!");
  const [simCards, setSimCards] = useState<SimCard[]>([]);
  const [selectedSimId, setSelectedSimId] = useState<number | undefined>(undefined);
  const [isLoading, setIsLoading] = useState(false);
  const [statusText, setStatusText] = useState("");

  /** Check device SMS capability */
  const handleCheckCapability = async () => {
    try {
      const result = await canSendSms();
      Alert.alert(
        "Capability Check",
        `Capable: ${result.capable}${result.reason ? `\nReason: ${result.reason}` : ""}`
      );
    } catch (err: any) {
      Alert.alert("Capability Error", err?.message || String(err));
    }
  };

  /** Fetch SIM Cards */
  const handleGetSimCards = async (): Promise<SimCard[]> => {
    const simPermission = await requestPermission(
      PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE
    );
    if (!simPermission) {
      Alert.alert("Permission Denied", "Cannot access SIM card information.");
      return [];
    }

    try {
      const sims = await getSimCards();
      setSimCards(sims);
      if (sims.length > 0 && selectedSimId === undefined) {
        setSelectedSimId(sims[0].id);
      }
      setStatusText(`Found ${sims.length} SIM card(s)`);
      return sims;
    } catch (error: any) {
      console.error("Failed to get SIM cards:", error);
      Alert.alert("Error", `Could not retrieve SIM cards: ${error?.message || error}`);
      return [];
    }
  };

  /** Handles retrieving SIM cards and sending SMS */
  const handleSendSms = async () => {
    const targetNumber = phoneNumber.trim() || "REAL_PHONE_NUMBER";
    const textToSend = message.trim() || "Hello!";

    setIsLoading(true);
    setStatusText("Requesting permissions...");

    // 1. Request SIM info permission and fetch SIMs
    const sims = await handleGetSimCards();

    // 2. Request SMS permission
    const smsPermission = await requestPermission(
      PermissionsAndroid.PERMISSIONS.SEND_SMS
    );
    if (!smsPermission) {
      Alert.alert("Permission Denied", "Cannot send SMS.");
      setIsLoading(false);
      setStatusText("SMS Permission Denied");
      return;
    }

    // 3. Send SMS
    try {
      setStatusText("Sending SMS...");

      // If user has a selected SIM or detected SIMs, pass the SIM id; otherwise system default
      const simToUse = selectedSimId !== undefined ? selectedSimId : (sims[0]?.id);

      const result = await sendSms(targetNumber, textToSend, simToUse);

      console.log("Send SMS result:", result);
      setStatusText("SMS sent successfully!");
      Alert.alert("Success", `Message sent successfully to ${targetNumber}!`);
    } catch (error: any) {
      console.error("Failed to send SMS:", error);
      setStatusText(`Failed: ${error?.message || error}`);
      Alert.alert("Error", `Could not send SMS: ${error?.message || error}`);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.card}>
          <Text style={styles.title}>Expo Android SMS Sender</Text>
          <Text style={styles.subtitle}>Expo SDK 57 Example</Text>

          <View style={styles.formGroup}>
            <Text style={styles.label}>Recipient Phone Number</Text>
            <TextInput
              style={styles.input}
              placeholder="e.g. +1234567890 or REAL_PHONE_NUMBER"
              placeholderTextColor="#8e8e93"
              value={phoneNumber}
              onChangeText={setPhoneNumber}
              keyboardType="phone-pad"
            />
          </View>

          <View style={styles.formGroup}>
            <Text style={styles.label}>SMS Message</Text>
            <TextInput
              style={[styles.input, styles.multilineInput]}
              placeholder="Enter message body"
              placeholderTextColor="#8e8e93"
              value={message}
              onChangeText={setMessage}
              multiline
              numberOfLines={3}
            />
          </View>

          <View style={styles.buttonGroup}>
            <TouchableOpacity
              style={[styles.button, styles.secondaryButton]}
              onPress={handleGetSimCards}
              disabled={isLoading}
            >
              <Text style={styles.secondaryButtonText}>Detect SIM Cards</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.button, styles.secondaryButton]}
              onPress={handleCheckCapability}
              disabled={isLoading}
            >
              <Text style={styles.secondaryButtonText}>Check Capability</Text>
            </TouchableOpacity>
          </View>

          {simCards.length > 0 && (
            <View style={styles.simContainer}>
              <Text style={styles.sectionHeading}>Detected SIM Cards</Text>
              {simCards.map((sim, index) => {
                const isSelected = selectedSimId === sim.id;
                return (
                  <TouchableOpacity
                    key={sim.id ?? index}
                    style={[styles.simItem, isSelected && styles.simItemSelected]}
                    onPress={() => setSelectedSimId(sim.id)}
                  >
                    <View style={styles.simRadio}>
                      {isSelected && <View style={styles.simRadioInner} />}
                    </View>
                    <View style={styles.simTextContainer}>
                      <Text style={styles.simTitle}>
                        SIM {index + 1}: {sim.carrierName || "Unknown Carrier"}
                      </Text>
                      <Text style={styles.simDetail}>
                        Display: {sim.displayName} | Slot: {sim.slotIndex} | ID: {sim.id}
                      </Text>
                    </View>
                  </TouchableOpacity>
                );
              })}
            </View>
          )}

          <TouchableOpacity
            style={[styles.button, styles.primaryButton, isLoading && styles.buttonDisabled]}
            onPress={handleSendSms}
            disabled={isLoading}
          >
            {isLoading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.primaryButtonText}>Send SMS</Text>
            )}
          </TouchableOpacity>

          {statusText ? <Text style={styles.statusText}>{statusText}</Text> : null}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f2f4f8",
  },
  scrollContent: {
    padding: 20,
    justifyContent: "center",
    flexGrow: 1,
  },
  card: {
    backgroundColor: "#ffffff",
    borderRadius: 16,
    padding: 24,
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 3,
  },
  title: {
    fontSize: 22,
    fontWeight: "700",
    color: "#1a1a1a",
    textAlign: "center",
  },
  subtitle: {
    fontSize: 14,
    color: "#6c757d",
    textAlign: "center",
    marginBottom: 24,
    marginTop: 4,
  },
  formGroup: {
    marginBottom: 16,
  },
  label: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333333",
    marginBottom: 6,
  },
  input: {
    backgroundColor: "#f8f9fa",
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 15,
    color: "#1a1a1a",
  },
  multilineInput: {
    minHeight: 70,
    textAlignVertical: "top",
  },
  buttonGroup: {
    flexDirection: "row",
    gap: 10,
    marginBottom: 16,
  },
  button: {
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
  primaryButton: {
    backgroundColor: "#007bff",
    marginTop: 8,
  },
  primaryButtonText: {
    color: "#ffffff",
    fontSize: 16,
    fontWeight: "700",
  },
  secondaryButton: {
    flex: 1,
    backgroundColor: "#eef2f6",
  },
  secondaryButtonText: {
    color: "#334155",
    fontSize: 13,
    fontWeight: "600",
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  simContainer: {
    marginBottom: 16,
    backgroundColor: "#f8fafc",
    padding: 12,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: "#e2e8f0",
  },
  sectionHeading: {
    fontSize: 13,
    fontWeight: "700",
    color: "#475569",
    marginBottom: 8,
    textTransform: "uppercase",
  },
  simItem: {
    flexDirection: "row",
    alignItems: "center",
    padding: 10,
    borderRadius: 8,
    backgroundColor: "#ffffff",
    marginBottom: 6,
    borderWidth: 1,
    borderColor: "#e2e8f0",
  },
  simItemSelected: {
    borderColor: "#007bff",
    backgroundColor: "#eff6ff",
  },
  simRadio: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: "#007bff",
    alignItems: "center",
    justifyContent: "center",
    marginRight: 10,
  },
  simRadioInner: {
    width: 9,
    height: 9,
    borderRadius: 4.5,
    backgroundColor: "#007bff",
  },
  simTextContainer: {
    flex: 1,
  },
  simTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#1e293b",
  },
  simDetail: {
    fontSize: 12,
    color: "#64748b",
    marginTop: 2,
  },
  statusText: {
    marginTop: 14,
    textAlign: "center",
    fontSize: 13,
    color: "#64748b",
  },
});
