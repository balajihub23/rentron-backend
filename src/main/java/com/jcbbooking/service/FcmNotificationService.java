package com.jcbbooking.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import com.jcbbooking.model.DeviceToken;
import com.jcbbooking.repository.DeviceTokenRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FcmNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final com.jcbbooking.repository.BookingSettingRepository bookingSettingRepository;

    @Value("${firebase.config.path:}")
    private String firebaseConfigPath;

    @Value("${firebase.credentials.json:}")
    private String firebaseCredentialsJson;

    private boolean isInitialized = false;

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                isInitialized = true;
                log.info("FirebaseApp is already initialized");
                return;
            }

            InputStream serviceAccountStream = null;
            if (firebaseCredentialsJson != null && !firebaseCredentialsJson.trim().isEmpty()) {
                serviceAccountStream = new ByteArrayInputStream(firebaseCredentialsJson.getBytes(StandardCharsets.UTF_8));
            } else if (firebaseConfigPath != null && !firebaseConfigPath.trim().isEmpty()) {
                serviceAccountStream = new FileInputStream(firebaseConfigPath);
            }

            if (serviceAccountStream == null) {
                InputStream cpStream = getClass().getClassLoader().getResourceAsStream("firebase-service-account.json");
                if (cpStream != null) {
                    serviceAccountStream = cpStream;
                } else {
                    java.io.File localFile = new java.io.File("firebase-service-account.json");
                    if (localFile.exists()) {
                        serviceAccountStream = new FileInputStream(localFile);
                    }
                }
            }

            if (serviceAccountStream != null) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
                        .build();
                FirebaseApp.initializeApp(options);
                isInitialized = true;
                log.info("Firebase Admin SDK initialized successfully for FCM push notifications");
            } else {
                log.info("Firebase credentials not provided via FIREBASE_CONFIG_PATH. Push notifications running in simulation/WebSocket fallback mode.");
            }
        } catch (Exception e) {
            log.warn("Could not initialize Firebase Admin SDK: {}. Using fallback notification channels.", e.getMessage());
            isInitialized = false;
        }
    }

    public boolean isFirebaseActive() {
        return isInitialized;
    }

    public void sendBookingOfferPush(Long userId, Long bookingId, String bookingNumber, String pickupAddress, Double estimatedFare) {
        String title = "New Booking Offer! #" + (bookingNumber != null ? bookingNumber : bookingId);
        String body = "Pickup: " + (pickupAddress != null ? pickupAddress : "Nearby Location") +
                " • Est. Fare: ₹" + (estimatedFare != null ? estimatedFare.intValue() : 0);

        Map<String, String> data = new HashMap<>();
        data.put("type", "NEW_BOOKING_OFFER");
        data.put("bookingId", String.valueOf(bookingId));
        data.put("bookingNumber", bookingNumber != null ? bookingNumber : "");
        data.put("click_action", "FLUTTER_NOTIFICATION_CLICK");

        sendNotificationToUser(userId, title, body, data);
    }

    public void sendBookingAssignedPush(Long userId, Long bookingId, String bookingNumber) {
        String title = "Booking Confirmed #" + bookingNumber;
        String body = "You have been assigned to booking #" + bookingNumber + ". Tap to start navigation.";

        Map<String, String> data = new HashMap<>();
        data.put("type", "BOOKING_ASSIGNED");
        data.put("bookingId", String.valueOf(bookingId));
        data.put("bookingNumber", bookingNumber != null ? bookingNumber : "");

        sendNotificationToUser(userId, title, body, data);
    }

    public void sendBookingCancelledPush(Long userId, Long bookingId, String bookingNumber, String reason) {
        String title = "Booking Cancelled #" + bookingNumber;
        String body = "Booking has been cancelled: " + (reason != null ? reason : "Customer cancelled request.");

        Map<String, String> data = new HashMap<>();
        data.put("type", "BOOKING_CANCELLED");
        data.put("bookingId", String.valueOf(bookingId));
        data.put("bookingNumber", bookingNumber != null ? bookingNumber : "");

        sendNotificationToUser(userId, title, body, data);
    }

    public void sendNotificationToUser(Long userId, String title, String body, Map<String, String> data) {
        if (!isInitialized) {
            log.debug("FCM not initialized. Simulated push notification dispatched to user ID {}: {} - {}", userId, title, body);
            return;
        }

        try {
            com.jcbbooking.model.BookingSetting setting = bookingSettingRepository.findBySettingKey("DEFAULT_SETTINGS").orElse(null);
            if (setting != null && Boolean.FALSE.equals(setting.getPushEnabled())) {
                log.info("Push notifications are disabled in admin settings. Skipping push alert for user ID {}", userId);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not check pushEnabled setting: {}", e.getMessage());
        }

        try {
            List<DeviceToken> activeTokens = deviceTokenRepository.findAllByUserIdAndActiveTrue(userId);
            if (activeTokens.isEmpty()) {
                log.debug("No active FCM device tokens registered for user ID {}", userId);
                return;
            }

            for (DeviceToken tokenRecord : activeTokens) {
                String token = tokenRecord.getDeviceToken();
                try {
                    Message message = Message.builder()
                            .setToken(token)
                            .setNotification(Notification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .build())
                            .putAllData(data != null ? data : Map.of())
                            .setAndroidConfig(AndroidConfig.builder()
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    .setNotification(AndroidNotification.builder()
                                            .setChannelId("rentron_booking_alerts")
                                            .setSound("default")
                                            .build())
                                    .build())
                            .build();

                    String response = FirebaseMessaging.getInstance().send(message);
                    log.info("FCM push sent successfully to user {} device token {}: message ID {}", userId, token, response);
                } catch (FirebaseMessagingException fme) {
                    MessagingErrorCode code = fme.getMessagingErrorCode();
                    log.warn("FCM error sending to token {}: {}", token, fme.getMessage());
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                        tokenRecord.setActive(false);
                        deviceTokenRepository.save(tokenRecord);
                        log.info("Deactivated invalid FCM device token: {}", token);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to dispatch FCM push notifications for user {}: {}", userId, e.getMessage());
        }
    }
}
