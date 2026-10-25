package com.jcbbooking.controller;

import com.jcbbooking.model.DeviceToken;
import com.jcbbooking.model.Role;
import com.jcbbooking.model.User;
import com.jcbbooking.model.UserNotification;
import com.jcbbooking.repository.DeviceTokenRepository;
import com.jcbbooking.repository.UserNotificationRepository;
import com.jcbbooking.repository.UserRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.util.ApiResponse;
import com.jcbbooking.websocket.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final UserNotificationRepository userNotificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @GetMapping("/inbox")
    public ResponseEntity<ApiResponse<List<UserNotification>>> getMyNotifications(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<UserNotification> notifications = userNotificationRepository.findAllByUserIdOrderByCreatedAtDesc(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success("Notifications retrieved successfully", notifications));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(@AuthenticationPrincipal CustomUserDetails userDetails) {
        long count = userNotificationRepository.countByUserIdAndIsReadFalse(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success("Unread count retrieved", count));
    }

    @PostMapping("/mark-read/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        userNotificationRepository.findById(id).ifPresent(n -> {
            if (n.getUserId().equals(userDetails.getId())) {
                n.setIsRead(true);
                userNotificationRepository.save(n);
            }
        });
        return ResponseEntity.ok(ApiResponse.success("Notification marked as read"));
    }

    @PostMapping("/mark-all-read")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@AuthenticationPrincipal CustomUserDetails userDetails) {
        List<UserNotification> list = userNotificationRepository.findAllByUserIdOrderByCreatedAtDesc(userDetails.getId());
        list.forEach(n -> n.setIsRead(true));
        userNotificationRepository.saveAll(list);
        return ResponseEntity.ok(ApiResponse.success("All notifications marked as read"));
    }

    @PostMapping("/notify-admin")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> notifyAdminUsers(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        String title = (String) payload.getOrDefault("title", "New Vehicle Registered");
        String message = (String) payload.getOrDefault("message", "A new vehicle registration has been submitted for review.");
        String notificationType = (String) payload.getOrDefault("notificationType", "VEHICLE");
        String deepLink = (String) payload.getOrDefault("deepLink", "/admin/approvals");

        List<User> adminUsers = userRepository.findAllByRole(Role.ADMIN);
        for (User admin : adminUsers) {
            UserNotification notification = UserNotification.builder()
                    .userId(admin.getId())
                    .targetType("ADMIN")
                    .notificationType(notificationType)
                    .title(title)
                    .message(message)
                    .deepLink(deepLink)
                    .isRead(false)
                    .build();
            userNotificationRepository.save(notification);

            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("type", "ADMIN_NOTIFICATION");
            wsPayload.put("title", title);
            wsPayload.put("message", message);
            wsPayload.put("notificationType", notificationType);
            wsPayload.put("deepLink", deepLink);
            webSocketNotificationService.sendBookingOfferToUser(admin.getId(), wsPayload);
        }

        log.info("Admin notification sent to {} admin users for vehicle submission by user ID {}", adminUsers.size(), userDetails != null ? userDetails.getId() : "anonymous");
        return ResponseEntity.ok(ApiResponse.success("Admins notified successfully"));
    }

    @PostMapping("/devices/register")
    @Transactional
    public ResponseEntity<ApiResponse<DeviceToken>> registerDeviceToken(
            @RequestParam(required = false) String deviceToken,
            @RequestParam(required = false, defaultValue = "ANDROID") String platform,
            @RequestParam(required = false) String deviceId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        String tokenVal = deviceToken;
        String platVal = platform;
        String didVal = deviceId;

        if (body != null) {
            if (body.containsKey("deviceToken")) tokenVal = body.get("deviceToken");
            if (body.containsKey("platform")) platVal = body.get("platform");
            if (body.containsKey("deviceId")) didVal = body.get("deviceId");
        }

        if (tokenVal == null || tokenVal.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("deviceToken is required"));
        }

        Long userId = userDetails != null ? userDetails.getId() : null;
        if (userId == null && body != null && body.containsKey("userId")) {
            try {
                userId = Long.parseLong(body.get("userId"));
            } catch (Exception ignored) {}
        }

        if (userId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Authenticated user or valid userId is required"));
        }

        final Long effectiveUserId = userId;
        final String effectiveToken = tokenVal.trim();
        final String effectivePlatform = platVal != null && !platVal.trim().isEmpty() ? platVal.trim().toUpperCase() : "ANDROID";
        final String effectiveDeviceId = (didVal != null && !didVal.trim().isEmpty()) ? didVal.trim() : null;

        // If user already has an entry in user_devices, update device_id and device_token instead of inserting a second entry
        List<DeviceToken> existingList = deviceTokenRepository.findAllByUserId(effectiveUserId);

        DeviceToken token;
        if (existingList != null && !existingList.isEmpty()) {
            token = existingList.get(0);

            // If a dummy token (like driver-token-X) is sent but existing token is a real fcm_ token, preserve the valid fcm token
            boolean isDummyToken = effectiveToken.startsWith("driver-token-") || "undefined".equalsIgnoreCase(effectiveToken) || "null".equalsIgnoreCase(effectiveToken);
            if (!isDummyToken || token.getDeviceToken() == null || token.getDeviceToken().trim().isEmpty() || token.getDeviceToken().startsWith("driver-token-")) {
                token.setDeviceToken(effectiveToken);
            }
            if (effectiveDeviceId != null) {
                token.setDeviceId(effectiveDeviceId);
            }
            token.setPlatform(effectivePlatform);
            token.setActive(true);
            token.setLastSeenAt(LocalDateTime.now());

            // Delete any duplicate entries for this user if they exist
            if (existingList.size() > 1) {
                for (int i = 1; i < existingList.size(); i++) {
                    deviceTokenRepository.delete(existingList.get(i));
                }
            }
        } else {
            token = DeviceToken.builder()
                    .userId(effectiveUserId)
                    .deviceToken(effectiveToken)
                    .platform(effectivePlatform)
                    .deviceId(effectiveDeviceId)
                    .active(true)
                    .lastSeenAt(LocalDateTime.now())
                    .build();
        }

        DeviceToken saved = deviceTokenRepository.save(token);
        log.info("Registered/updated deviceToken for userId: {}, deviceId: {}, platform: {}, token: {}",
                effectiveUserId, saved.getDeviceId(), saved.getPlatform(), saved.getDeviceToken());

        return ResponseEntity.ok(ApiResponse.success("Device token registered successfully", saved));
    }

    @DeleteMapping("/devices")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> unregisterDeviceToken(
            @RequestParam String deviceToken,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceTokenRepository.deleteAllByDeviceToken(deviceToken);
        return ResponseEntity.ok(ApiResponse.success("Device token removed"));
    }

    @PostMapping("/admin/send")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> sendTargetedNotification(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (userDetails.getUser().getRole() != Role.ADMIN) {
            return ResponseEntity.status(403).body(ApiResponse.error("Admin permission required"));
        }

        String targetType = (String) payload.getOrDefault("targetType", "ALL_USERS");
        String notificationType = (String) payload.getOrDefault("notificationType", "SYSTEM");
        String title = (String) payload.getOrDefault("title", "Announcement");
        String message = (String) payload.getOrDefault("message", "");
        String deepLink = (String) payload.get("deepLink");
        Long targetUserId = payload.get("targetUserId") != null ? Long.valueOf(payload.get("targetUserId").toString()) : null;

        List<User> targetUsers;

        switch (targetType.toUpperCase()) {
            case "INDIVIDUAL_USER" -> {
                if (targetUserId == null) return ResponseEntity.badRequest().body(ApiResponse.error("targetUserId required for INDIVIDUAL_USER"));
                targetUsers = userRepository.findById(targetUserId).map(List::of).orElse(List.of());
            }
            case "ALL_DRIVERS" -> targetUsers = userRepository.findAllByRole(Role.DRIVER);
            case "ALL_CUSTOMERS" -> targetUsers = userRepository.findAllByRole(Role.CUSTOMER);
            case "ALL_CONTRACTORS" -> targetUsers = userRepository.findAllByRole(Role.CONTRACTOR);
            case "ALL_USERS" -> targetUsers = userRepository.findAll();
            default -> { return ResponseEntity.badRequest().body(ApiResponse.error("Invalid targetType")); }
        }

        for (User user : targetUsers) {
            UserNotification notification = UserNotification.builder()
                    .userId(user.getId())
                    .targetType(targetType)
                    .notificationType(notificationType)
                    .title(title)
                    .message(message)
                    .deepLink(deepLink)
                    .isRead(false)
                    .build();
            userNotificationRepository.save(notification);

            // Real-time WebSocket delivery
            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("type", "INBOX_NOTIFICATION");
            wsPayload.put("title", title);
            wsPayload.put("message", message);
            wsPayload.put("notificationType", notificationType);
            wsPayload.put("deepLink", deepLink);
            webSocketNotificationService.sendBookingOfferToUser(user.getId(), wsPayload);
        }

        return ResponseEntity.ok(ApiResponse.success("Notification dispatched to " + targetUsers.size() + " users"));
    }
}
