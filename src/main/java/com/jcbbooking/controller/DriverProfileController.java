package com.jcbbooking.controller;

import com.jcbbooking.model.Driver;
import com.jcbbooking.model.Role;
import com.jcbbooking.model.UserPreference;
import com.jcbbooking.repository.DriverRepository;
import com.jcbbooking.repository.UserPreferenceRepository;
import com.jcbbooking.repository.UserRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/drivers/me")
@RequiredArgsConstructor
@Slf4j
public class DriverProfileController {

    private final DriverRepository driverRepository;
    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final com.jcbbooking.repository.PartnerApprovalRepository partnerApprovalRepository;
    private final com.jcbbooking.repository.BookingRepository bookingRepository;
    private final com.jcbbooking.repository.BookingAssignmentRepository bookingAssignmentRepository;
    private final com.jcbbooking.service.BookingAssignmentService bookingAssignmentService;
    private final com.jcbbooking.repository.BookingEventHistoryRepository bookingEventHistoryRepository;
    private final com.jcbbooking.websocket.WebSocketNotificationService webSocketNotificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<Driver>> getMyProfile(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request for authenticated driver profile: {}", userDetails != null ? userDetails.getId() : "null");
        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }

        String rawPhone = userDetails.getUser().getPhone();
        String cleanPhone = rawPhone != null ? rawPhone.replaceAll("[^0-9]", "") : "";
        if (cleanPhone.length() > 10) {
            cleanPhone = cleanPhone.substring(cleanPhone.length() - 10);
        }

        Driver driver = driverRepository.findByUserId(userDetails.getId()).orElse(null);
        if (driver == null && rawPhone != null) {
            driver = driverRepository.findByPhone(rawPhone).orElse(null);
        }

        // Fuzzy/Cleaned phone search across drivers
        if (driver == null && !cleanPhone.isEmpty()) {
            final String last10 = cleanPhone;
            driver = driverRepository.findAll().stream()
                    .filter(d -> d.getPhone() != null && d.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                    .findFirst().orElse(null);
        }

        // Fallback to PartnerApproval table
        if (driver == null && rawPhone != null) {
            final String phoneSearch = rawPhone;
            final String last10 = cleanPhone;
            com.jcbbooking.model.PartnerApproval pa = partnerApprovalRepository.findByPhone(phoneSearch)
                    .orElseGet(() -> partnerApprovalRepository.findAll().stream()
                            .filter(p -> p.getPhone() != null && p.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                            .findFirst().orElse(null));

            if (pa != null) {
                driver = Driver.builder()
                        .userId(userDetails.getId())
                        .fullName(pa.getFullName())
                        .phone(pa.getPhone())
                        .email(pa.getEmail())
                        .experience(pa.getExperience())
                        .operationType(pa.getOperationType())
                        .selectedRole(pa.getSelectedRole())
                        .status(pa.getStatus() != null ? pa.getStatus() : "PENDING_VERIFICATION")
                        .rating(pa.getRating() != null ? pa.getRating() : 4.0)
                        .totalJobs(0)
                        .totalEarnings(0.0)
                        .build();
                driver = driverRepository.save(driver);
                log.info("Created missing Driver profile for user ID {} from PartnerApproval phone {}", userDetails.getId(), pa.getPhone());
            }
        }

        // Final fallback: if authenticated user exists, create a draft Driver profile so data is NEVER null
        if (driver == null && userDetails.getUser() != null) {
            driver = Driver.builder()
                    .userId(userDetails.getId())
                    .fullName(userDetails.getUser().getFullName() != null ? userDetails.getUser().getFullName() : "Driver")
                    .phone(userDetails.getUser().getPhone())
                    .email(userDetails.getUser().getEmail())
                    .status("DRAFT")
                    .rating(4.0)
                    .totalJobs(0)
                    .totalEarnings(0.0)
                    .build();
            driver = driverRepository.save(driver);
            log.info("Created draft Driver profile for user ID {}", userDetails.getId());
        }

        if (driver != null) {
            if (driver.getSelectedRole() == null) {
                if ("FLEET".equalsIgnoreCase(driver.getOperationType())) {
                    driver.setSelectedRole("FLEET_OWNER");
                } else {
                    driver.setSelectedRole("DRIVER");
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success("Profile retrieved successfully", driver));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> updateMyProfile(
            @RequestBody Driver updateRequest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to update driver self profile: {}", userDetails.getId());

        Driver existing = driverRepository.findByUserId(userDetails.getId())
                .orElseGet(() -> driverRepository.findByPhone(userDetails.getUser().getPhone()).orElse(null));

        if (existing == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Driver profile not found"));
        }

        // Permitted self updates ONLY
        if (updateRequest.getFullName() != null) existing.setFullName(updateRequest.getFullName());
        if (updateRequest.getEmail() != null) existing.setEmail(updateRequest.getEmail());
        if (updateRequest.getLicenseNumber() != null) existing.setLicenseNumber(updateRequest.getLicenseNumber());
        if (updateRequest.getAadhaarNumber() != null) existing.setAadhaarNumber(updateRequest.getAadhaarNumber());
        if (updateRequest.getExperience() != null) existing.setExperience(updateRequest.getExperience());
        if (updateRequest.getOperationType() != null) existing.setOperationType(updateRequest.getOperationType());
        if (updateRequest.getSelectedRole() != null) existing.setSelectedRole(updateRequest.getSelectedRole());
        if (updateRequest.getSelectedVehicleType() != null) existing.setSelectedVehicleType(updateRequest.getSelectedVehicleType());
        if (updateRequest.getSelectedMachineryModel() != null) existing.setSelectedMachineryModel(updateRequest.getSelectedMachineryModel());
        if (updateRequest.getCity() != null) existing.setCity(updateRequest.getCity());
        if (updateRequest.getDob() != null) existing.setDob(updateRequest.getDob());
        if (updateRequest.getGender() != null) existing.setGender(updateRequest.getGender());
        if (updateRequest.getAddress() != null) existing.setAddress(updateRequest.getAddress());
        if (updateRequest.getProfilePhotoUrl() != null) existing.setProfilePhotoUrl(updateRequest.getProfilePhotoUrl());
        if (updateRequest.getPreferredLanguage() != null) existing.setPreferredLanguage(updateRequest.getPreferredLanguage());

        Driver saved = driverRepository.save(existing);

        // Also sync to PartnerApproval
        partnerApprovalRepository.findByPhone(existing.getPhone()).ifPresent(pa -> {
            if (updateRequest.getFullName() != null) pa.setFullName(updateRequest.getFullName());
            if (updateRequest.getEmail() != null) pa.setEmail(updateRequest.getEmail());
            if (updateRequest.getOperationType() != null) pa.setOperationType(updateRequest.getOperationType());
            if (updateRequest.getSelectedRole() != null) pa.setSelectedRole(updateRequest.getSelectedRole());
            partnerApprovalRepository.save(pa);
        });

        // Also sync name/email on User entity
        userRepository.findById(userDetails.getId()).ifPresent(user -> {
            if (updateRequest.getFullName() != null) user.setFullName(updateRequest.getFullName());
            if (updateRequest.getEmail() != null) user.setEmail(updateRequest.getEmail());
            userRepository.save(user);
        });

        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", saved));
    }

    @PostMapping("/location")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> updateLocation(
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double gpsAccuracy,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Double effectiveLat = latitude;
        Double effectiveLng = longitude;
        Double effectiveAcc = gpsAccuracy != null ? gpsAccuracy : 10.0;

        if (body != null) {
            if (body.containsKey("latitude")) effectiveLat = Double.valueOf(body.get("latitude").toString());
            else if (body.containsKey("lat")) effectiveLat = Double.valueOf(body.get("lat").toString());
            if (body.containsKey("longitude")) effectiveLng = Double.valueOf(body.get("longitude").toString());
            else if (body.containsKey("lng")) effectiveLng = Double.valueOf(body.get("lng").toString());
            if (body.containsKey("gpsAccuracy")) effectiveAcc = Double.valueOf(body.get("gpsAccuracy").toString());
            else if (body.containsKey("accuracy")) effectiveAcc = Double.valueOf(body.get("accuracy").toString());
        }

        if (effectiveLat == null || effectiveLng == null || effectiveLat < -90 || effectiveLat > 90 || effectiveLng < -180 || effectiveLng > 180) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid GPS coordinates"));
        }

        Driver driver = driverRepository.findByUserId(userDetails.getId())
                .orElseGet(() -> driverRepository.findByPhone(userDetails.getUser().getPhone()).orElse(null));

        if (driver != null) {
            driver.setLatitude(effectiveLat);
            driver.setLongitude(effectiveLng);
            driver.setGpsAccuracy(effectiveAcc);
            driver.setLocationUpdatedAt(LocalDateTime.now());
            driverRepository.save(driver);

            // Broadcast real-time location to active observers
            webSocketNotificationService.broadcastDriverStatus(driver.getId(), driver.getUserId(), Boolean.TRUE.equals(driver.getIsOnline()), driver.getSelectedVehicleType(), effectiveLat, effectiveLng);
        }

        return ResponseEntity.ok(ApiResponse.success("Location updated successfully"));
    }

    @PostMapping("/online-status")
    @Transactional
    public ResponseEntity<ApiResponse<Boolean>> toggleOnlineStatus(
            @RequestParam(required = false) Boolean isOnline,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Boolean targetOnline = isOnline;
        if (body != null) {
            if (body.containsKey("isOnline")) targetOnline = Boolean.valueOf(body.get("isOnline").toString());
            else if (body.containsKey("online")) targetOnline = Boolean.valueOf(body.get("online").toString());
        }
        if (targetOnline == null) {
            targetOnline = true;
        }

        log.info("REST request to toggle online status for user ID {} to {}", userDetails.getId(), targetOnline);

        Driver driver = driverRepository.findByUserId(userDetails.getId())
                .orElseGet(() -> driverRepository.findByPhone(userDetails.getUser().getPhone()).orElse(null));

        if (driver == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Driver profile not found"));
        }

        if (Boolean.TRUE.equals(targetOnline)) {
            if ("SUSPENDED".equalsIgnoreCase(driver.getStatus()) || "REJECTED".equalsIgnoreCase(driver.getStatus())) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error("Driver account is not eligible to go online (Status: " + driver.getStatus() + ")"));
            }
        }

        driver.setIsOnline(targetOnline);
        if (Boolean.TRUE.equals(targetOnline)) {
            driver.setLocationUpdatedAt(LocalDateTime.now());
            if (!"SUSPENDED".equalsIgnoreCase(driver.getStatus())) {
                driver.setStatus("ACTIVE");
            }
        } else {
            if (!"SUSPENDED".equalsIgnoreCase(driver.getStatus())) {
                driver.setStatus("OFFLINE");
            }
        }
        driverRepository.save(driver);

        // Real-time WebSocket broadcast so Customer App updates drivers without refresh
        webSocketNotificationService.broadcastDriverStatus(
                driver.getId(),
                driver.getUserId(),
                Boolean.TRUE.equals(targetOnline),
                driver.getSelectedVehicleType(),
                driver.getLatitude(),
                driver.getLongitude()
        );

        return ResponseEntity.ok(ApiResponse.success("Online status updated to " + targetOnline, targetOnline));
    }

    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<UserPreference>> getPreferences(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserPreference pref = userPreferenceRepository.findByUserId(userDetails.getId())
                .orElseGet(() -> {
                    UserPreference defaultPref = UserPreference.builder()
                            .userId(userDetails.getId())
                            .themeMode("SYSTEM")
                            .preferredLanguage("English")
                            .build();
                    return userPreferenceRepository.save(defaultPref);
                });

        return ResponseEntity.ok(ApiResponse.success("Preferences retrieved", pref));
    }

    @PutMapping("/preferences")
    @Transactional
    public ResponseEntity<ApiResponse<UserPreference>> updatePreferences(
            @RequestBody UserPreference prefRequest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        UserPreference pref = userPreferenceRepository.findByUserId(userDetails.getId())
                .orElseGet(() -> UserPreference.builder().userId(userDetails.getId()).build());

        if (prefRequest.getThemeMode() != null) pref.setThemeMode(prefRequest.getThemeMode());
        if (prefRequest.getPreferredLanguage() != null) pref.setPreferredLanguage(prefRequest.getPreferredLanguage());
        if (prefRequest.getPushEnabled() != null) pref.setPushEnabled(prefRequest.getPushEnabled());
        if (prefRequest.getBookingOffersEnabled() != null) pref.setBookingOffersEnabled(prefRequest.getBookingOffersEnabled());
        if (prefRequest.getBookingUpdatesEnabled() != null) pref.setBookingUpdatesEnabled(prefRequest.getBookingUpdatesEnabled());
        if (prefRequest.getPaymentNotificationsEnabled() != null) pref.setPaymentNotificationsEnabled(prefRequest.getPaymentNotificationsEnabled());
        if (prefRequest.getSupportNotificationsEnabled() != null) pref.setSupportNotificationsEnabled(prefRequest.getSupportNotificationsEnabled());
        if (prefRequest.getPromotionalNotificationsEnabled() != null) pref.setPromotionalNotificationsEnabled(prefRequest.getPromotionalNotificationsEnabled());

        UserPreference saved = userPreferenceRepository.save(pref);
        return ResponseEntity.ok(ApiResponse.success("Preferences updated", saved));
    }

    private Driver getAuthenticatedDriver(CustomUserDetails userDetails) {
        if (userDetails == null || userDetails.getUser() == null) {
            return null;
        }
        Driver driver = driverRepository.findByUserId(userDetails.getId()).orElse(null);
        if (driver == null && userDetails.getUser().getPhone() != null) {
            driver = driverRepository.findByPhone(userDetails.getUser().getPhone()).orElse(null);
        }
        return driver;
    }

    @GetMapping("/offers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPendingOffers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        List<com.jcbbooking.model.BookingAssignment> offers = bookingAssignmentRepository
                .findAllByCandidateTypeAndCandidateIdAndStatusOrderByOfferedAtDesc("DRIVER", driver.getId(), "OFFERED");

        List<Map<String, Object>> response = new ArrayList<>();
        for (com.jcbbooking.model.BookingAssignment ba : offers) {
            Map<String, Object> map = new HashMap<>();
            map.put("assignmentId", ba.getId());
            map.put("bookingId", ba.getBookingId());
            map.put("layerIndex", ba.getLayerIndex());
            map.put("distanceKm", ba.getDistanceKm());
            map.put("offeredAt", ba.getOfferedAt());
            map.put("status", ba.getStatus());

            bookingRepository.findById(ba.getBookingId()).ifPresent(b -> {
                map.put("bookingNumber", b.getBookingNumber());
                map.put("bookingType", b.getBookingType());
                map.put("pickupAddress", b.getPickupAddressText());
                map.put("destinationAddress", b.getDestinationAddressText());
                map.put("totalAmount", b.getTotalAmount());
                map.put("scheduledPickupAt", b.getScheduledPickupAt());
            });
            response.add(map);
        }

        return ResponseEntity.ok(ApiResponse.success("Pending offers retrieved", response));
    }

    @PostMapping("/offers/{bookingId}/accept")
    public ResponseEntity<ApiResponse<String>> acceptOffer(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        boolean accepted = bookingAssignmentService.acceptBookingOffer(bookingId, "DRIVER", driver.getId(), userDetails.getId());
        if (accepted) {
            return ResponseEntity.ok(ApiResponse.success("Offer accepted successfully", "ASSIGNED"));
        } else {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Offer expired or already assigned to another driver"));
        }
    }

    @PostMapping("/offers/{bookingId}/reject")
    public ResponseEntity<ApiResponse<String>> rejectOffer(
            @PathVariable Long bookingId,
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        String effectiveReason = reason;
        if (body != null) {
            if (body.containsKey("reason")) effectiveReason = (String) body.get("reason");
            else if (body.containsKey("declineReason")) effectiveReason = (String) body.get("declineReason");
        }

        boolean rejected = bookingAssignmentService.rejectBookingOffer(bookingId, "DRIVER", driver.getId(), userDetails.getId(), effectiveReason);
        if (rejected) {
            return ResponseEntity.ok(ApiResponse.success("Offer rejected successfully", "REJECTED"));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("No active offer found to reject"));
        }
    }

    @GetMapping("/active-job")
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> getActiveJob(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        List<String> activeStatuses = List.of("ASSIGNED", "ACCEPTED", "DRIVER_ON_THE_WAY", "ARRIVED", "STARTED", "IN_PROGRESS");
        com.jcbbooking.model.Booking active = bookingRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(driver.getId(), activeStatuses).orElse(null);

        return ResponseEntity.ok(ApiResponse.success("Active job retrieved", active));
    }

    @PostMapping("/bookings/{id}/arriving")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> markArriving(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        com.jcbbooking.model.Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || !driver.getId().equals(booking.getDriverId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(ApiResponse.error("Booking not assigned to authenticated driver"));
        }

        booking.setStatus("DRIVER_ON_THE_WAY");
        com.jcbbooking.model.Booking saved = bookingRepository.save(booking);
        webSocketNotificationService.sendBookingLifecycleEvent(id, "DRIVER_ON_THE_WAY", Map.of("bookingId", id, "status", "DRIVER_ON_THE_WAY"));

        return ResponseEntity.ok(ApiResponse.success("Status updated to DRIVER_ON_THE_WAY", saved));
    }

    @PostMapping("/bookings/{id}/arrived")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> markArrived(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        com.jcbbooking.model.Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || !driver.getId().equals(booking.getDriverId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(ApiResponse.error("Booking not assigned to authenticated driver"));
        }

        booking.setStatus("ARRIVED");
        booking.setDriverArrivedAt(LocalDateTime.now());
        com.jcbbooking.model.Booking saved = bookingRepository.save(booking);
        webSocketNotificationService.sendBookingLifecycleEvent(id, "DRIVER_ARRIVED", Map.of("bookingId", id, "status", "ARRIVED"));

        return ResponseEntity.ok(ApiResponse.success("Status updated to ARRIVED", saved));
    }

    @PostMapping("/bookings/{id}/verify-otp")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> verifyOtpAndStartRide(
            @PathVariable Long id,
            @RequestParam(required = false) String otp,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        String effectiveOtp = otp;
        if (body != null && body.containsKey("otp")) {
            effectiveOtp = body.get("otp").toString();
        }
        if (effectiveOtp == null || effectiveOtp.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("OTP is required to start the ride"));
        }

        com.jcbbooking.model.Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || !driver.getId().equals(booking.getDriverId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(ApiResponse.error("Booking not assigned to authenticated driver"));
        }

        // Check if OTP was already verified/used (One-time use protection)
        if (booking.getOtpVerifiedAt() != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("OTP_ALREADY_USED: This OTP has already been verified for this booking"));
        }

        // Check if OTP has expired
        if (booking.getOtpExpiresAt() != null && LocalDateTime.now().isAfter(booking.getOtpExpiresAt())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("OTP_EXPIRED: The ride start OTP has expired. Please request a new OTP"));
        }

        // Check retry limit (lockout after max attempts)
        int attempts = booking.getOtpAttempts() != null ? booking.getOtpAttempts() : 0;
        int maxAttempts = booking.getOtpMaxAttempts() != null ? booking.getOtpMaxAttempts() : 5;
        if (attempts >= maxAttempts) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("OTP_LOCKED: Maximum OTP verification attempts (" + maxAttempts + ") exceeded. Contact support"));
        }

        String inputHash = hashOtp(effectiveOtp);
        boolean matches = (booking.getOtp() != null && booking.getOtp().trim().equals(effectiveOtp.trim()))
                || (booking.getRideStartOtpHash() != null && booking.getRideStartOtpHash().equals(inputHash));

        if (!matches) {
            booking.setOtpAttempts(attempts + 1);
            bookingRepository.save(booking);
            int remaining = maxAttempts - (attempts + 1);
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_OTP: Entered OTP does not match. Remaining attempts: " + Math.max(0, remaining)));
        }

        booking.setStatus("STARTED");
        booking.setOtpVerifiedAt(LocalDateTime.now());
        booking.setOtp(null); // One-time use: Clear plaintext OTP to prevent replay or leaks
        booking.setRideStartedAt(LocalDateTime.now());
        com.jcbbooking.model.Booking saved = bookingRepository.save(booking);
        webSocketNotificationService.sendBookingLifecycleEvent(id, "RIDE_STARTED", Map.of("bookingId", id, "status", "STARTED"));

        return ResponseEntity.ok(ApiResponse.success("OTP verified and ride started", saved));
    }

    @PostMapping("/bookings/{id}/start")
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> startRide(
            @PathVariable Long id,
            @RequestParam(required = false) String otp,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return verifyOtpAndStartRide(id, otp, body, userDetails);
    }

    @PostMapping("/bookings/{id}/complete")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> completeRide(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        com.jcbbooking.model.Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || !driver.getId().equals(booking.getDriverId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(ApiResponse.error("Booking not assigned to authenticated driver"));
        }

        Double overrideFare = null;
        if (body != null && body.containsKey("finalFare") && body.get("finalFare") != null) {
            try {
                overrideFare = Double.valueOf(body.get("finalFare").toString());
            } catch (Exception ignored) {}
        }

        booking.setStatus("COMPLETED");
        booking.setRideCompletedAt(LocalDateTime.now());
        if (overrideFare != null && overrideFare > 0.0) {
            booking.setFinalFare(overrideFare);
        } else if (booking.getFinalFare() == null || booking.getFinalFare() <= 0.0) {
            booking.setFinalFare(booking.getTotalAmount());
        }
        com.jcbbooking.model.Booking saved = bookingRepository.save(booking);

        // Update driver stats
        driver.setTotalJobs((driver.getTotalJobs() != null ? driver.getTotalJobs() : 0) + 1);
        double prevEarnings = driver.getTotalEarnings() != null ? driver.getTotalEarnings() : 0.0;
        driver.setTotalEarnings(prevEarnings + (saved.getDriverAmount() != null ? saved.getDriverAmount() : 0.0));
        driverRepository.save(driver);

        webSocketNotificationService.sendBookingLifecycleEvent(id, "RIDE_COMPLETED", Map.of("bookingId", id, "status", "COMPLETED", "finalFare", saved.getFinalFare()));

        return ResponseEntity.ok(ApiResponse.success("Ride completed successfully", saved));
    }

    @PostMapping("/bookings/{id}/proof")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Booking>> uploadCompletionProof(
            @PathVariable Long id,
            @RequestParam(required = false) String photoUrl,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        String effectiveUrl = photoUrl;
        if (body != null && body.containsKey("photoUrl")) {
            effectiveUrl = (String) body.get("photoUrl");
        }
        if (effectiveUrl == null || effectiveUrl.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("photoUrl is required"));
        }

        com.jcbbooking.model.Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || !driver.getId().equals(booking.getDriverId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(ApiResponse.error("Booking not assigned to authenticated driver"));
        }

        booking.setWorksitePhotoUrl(effectiveUrl.trim());
        com.jcbbooking.model.Booking saved = bookingRepository.save(booking);
        return ResponseEntity.ok(ApiResponse.success("Completion proof uploaded successfully", saved));
    }

    @GetMapping("/job-history")
    public ResponseEntity<ApiResponse<List<com.jcbbooking.model.Booking>>> getJobHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Driver driver = getAuthenticatedDriver(userDetails);
        if (driver == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized driver"));
        }

        List<com.jcbbooking.model.Booking> jobs = bookingRepository.findAllByDriverIdOrderByCreatedAtDesc(driver.getId());
        return ResponseEntity.ok(ApiResponse.success("Job history retrieved", jobs));
    }

    private static String hashOtp(String otp) {
        if (otp == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(otp.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return otp;
        }
    }
}
