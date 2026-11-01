package com.jcbbooking.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendBookingLifecycleEvent(Long bookingId, String eventType, Map<String, Object> payload) {
        log.info("Publishing STOMP lifecycle event '{}' for booking ID {}", eventType, bookingId);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("bookingId", bookingId);
            event.put("eventType", eventType);
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", payload != null ? payload : Map.of());

            // Destination 1: Topic dedicated to this specific booking ID
            messagingTemplate.convertAndSend("/topic/booking/" + bookingId, (Object) event);

            // Destination 2: General bookings topic for live tracking observers
            messagingTemplate.convertAndSend("/topic/bookings/events", (Object) event);
        } catch (Exception e) {
            log.error("Failed to publish STOMP booking lifecycle event {}: {}", eventType, e.getMessage());
        }
    }

    public void sendBookingEventToUser(Long userId, String eventType, Map<String, Object> payload) {
        log.info("Sending STOMP event '{}' to user ID {}", eventType, userId);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", eventType);
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", payload != null ? payload : Map.of());

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    "/queue/booking-events",
                    (Object) event
            );
            messagingTemplate.convertAndSend("/topic/user-events/" + userId, (Object) event);
        } catch (Exception e) {
            log.error("Failed to send STOMP event to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendBookingOfferToUser(Long userId, Map<String, Object> offerPayload) {
        log.info("Sending STOMP booking offer to user ID {}", userId);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", "DRIVER_OFFER");
            event.put("type", "NEW_BOOKING_OFFER");
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", offerPayload);
            if (offerPayload != null) {
                event.putAll(offerPayload);
            }

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    "/queue/booking-offers",
                    (Object) event
            );
            messagingTemplate.convertAndSend("/topic/booking-offers/" + userId, (Object) event);

            if (offerPayload != null) {
                if (offerPayload.containsKey("driverId") && offerPayload.get("driverId") != null) {
                    messagingTemplate.convertAndSend("/topic/booking-offers/" + offerPayload.get("driverId"), (Object) event);
                }
                if (offerPayload.containsKey("candidateId") && offerPayload.get("candidateId") != null) {
                    messagingTemplate.convertAndSend("/topic/booking-offers/" + offerPayload.get("candidateId"), (Object) event);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send WebSocket booking offer to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendOfferCancelledToUser(Long userId, Map<String, Object> cancelPayload) {
        log.info("Sending STOMP offer cancellation to user ID {}", userId);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", "OFFER_EXPIRED");
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", cancelPayload);

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    "/queue/booking-offers",
                    (Object) event
            );
            messagingTemplate.convertAndSend("/topic/booking-offers/" + userId, (Object) event);
        } catch (Exception e) {
            log.error("Failed to send WebSocket offer cancellation to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendSupportMessageToUser(Long userId, Map<String, Object> supportPayload) {
        log.info("Sending STOMP support message notification to user ID {}", userId);
        try {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    "/queue/support-messages",
                    (Object) supportPayload
            );
            messagingTemplate.convertAndSend("/topic/support-messages/" + userId, (Object) supportPayload);
            messagingTemplate.convertAndSend("/topic/booking-offers/" + userId, (Object) supportPayload);
        } catch (Exception e) {
            log.error("Failed to send WebSocket support message to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendAdminAssignmentFailedNotification(Map<String, Object> adminPayload) {
        log.warn("Sending STOMP admin notification for failed booking assignment");
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", "NO_DRIVER_AVAILABLE");
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", adminPayload);

            messagingTemplate.convertAndSend("/topic/admin/booking-alerts", (Object) event);
        } catch (Exception e) {
            log.error("Failed to send WebSocket admin alert: {}", e.getMessage());
        }
    }

    public void sendOperatorInvitationToDriver(String phone, Map<String, Object> invitationPayload) {
        log.info("Sending STOMP operator invitation to driver phone {}", phone);
        try {
            messagingTemplate.convertAndSend("/topic/driver-invitations/" + phone, (Object) invitationPayload);
            messagingTemplate.convertAndSend("/topic/booking-offers/" + phone, (Object) invitationPayload);
        } catch (Exception e) {
            log.error("Failed to send WebSocket operator invitation to phone {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Broadcasts real-time driver online/offline/location change so Customer App updates live without refresh.
     */
    public void broadcastDriverStatus(Long driverId, Long userId, boolean isOnline, String vehicleType, Double lat, Double lng) {
        log.info("Broadcasting driver status to /topic/drivers/status: driverId={}, isOnline={}, vehicleType={}", driverId, isOnline, vehicleType);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", isOnline ? "DRIVER_ONLINE" : "DRIVER_OFFLINE");
            event.put("driverId", driverId);
            event.put("userId", userId);
            event.put("isOnline", isOnline);
            event.put("vehicleType", vehicleType);
            event.put("latitude", lat);
            event.put("longitude", lng);
            event.put("timestamp", LocalDateTime.now().toString());

            messagingTemplate.convertAndSend("/topic/drivers/status", (Object) event);
        } catch (Exception e) {
            log.error("Failed to broadcast driver status via WebSocket: {}", e.getMessage());
        }
    }

    /**
     * Sends scheduled ride intimate alert to driver 15/10 mins before pickup.
     */
    public void sendScheduledRideAlertToDriver(Long userId, Map<String, Object> alertPayload) {
        log.info("Sending STOMP scheduled ride intimate alert to user ID {}", userId);
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "EVT-" + UUID.randomUUID().toString());
            event.put("eventType", "SCHEDULED_RIDE_ALERT");
            event.put("type", "SCHEDULED_RIDE_ALERT");
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("version", "1.0");
            event.put("payload", alertPayload);
            if (alertPayload != null) {
                event.putAll(alertPayload);
            }

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId),
                    "/queue/booking-offers",
                    (Object) event
            );
            messagingTemplate.convertAndSend("/topic/booking-offers/" + userId, (Object) event);
            messagingTemplate.convertAndSend("/topic/scheduled-rides", (Object) event);
        } catch (Exception e) {
            log.error("Failed to send WebSocket scheduled ride alert to user {}: {}", userId, e.getMessage());
        }
    }
}

