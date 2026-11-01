package com.jcbbooking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcbbooking.dto.QuotationRequest;
import com.jcbbooking.dto.QuotationResponse;
import com.jcbbooking.exception.ResourceNotFoundException;
import com.jcbbooking.model.*;
import com.jcbbooking.repository.*;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.service.BookingAssignmentService;
import com.jcbbooking.service.PricingService;
import com.jcbbooking.util.ApiResponse;
import com.jcbbooking.websocket.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingRepository bookingRepository;
    private final CartRepository cartRepository;
    private final ContractorRepository contractorRepository;
    private final DriverRepository driverRepository;
    private final PricingService pricingService;
    private final BookingAssignmentService bookingAssignmentService;
    private final BookingEventHistoryRepository bookingEventHistoryRepository;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final WebSocketNotificationService webSocketNotificationService;
    private final ProductRepository productRepository;
    private final RentalPackageRepository rentalPackageRepository;
    private final com.jcbbooking.service.FcmNotificationService fcmNotificationService;
    private final com.jcbbooking.service.scheduler.BookingSchedulerService bookingSchedulerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<ApiResponse<List<Booking>>> getBookings(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get bookings for user ID: {}", userDetails != null ? userDetails.getId() : "anonymous");
        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }

        Role role = userDetails.getUser().getRole();

        if (role == Role.ADMIN) {
            return ResponseEntity.ok(ApiResponse.success("Bookings retrieved successfully", bookingRepository.findAll()));
        } else if (role == Role.CONTRACTOR) {
            Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
            if (conOpt.isEmpty()) {
                conOpt = contractorRepository.findByPhone(userDetails.getUser().getPhone());
            }
            if (conOpt.isPresent()) {
                List<Booking> contractorBookings = bookingRepository.findAllByContractorIdOrderByCreatedAtDesc(conOpt.get().getId());
                return ResponseEntity.ok(ApiResponse.success("Contractor bookings retrieved successfully", contractorBookings));
            }
            return ResponseEntity.ok(ApiResponse.success("No bookings found", List.of()));
        } else if (role == Role.DRIVER) {
            Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
            if (drvOpt.isEmpty()) {
                drvOpt = driverRepository.findByPhone(userDetails.getUser().getPhone());
            }
            if (drvOpt.isPresent()) {
                List<Booking> driverBookings = bookingRepository.findAllByDriverIdOrderByCreatedAtDesc(drvOpt.get().getId());
                return ResponseEntity.ok(ApiResponse.success("Driver bookings retrieved successfully", driverBookings));
            }
            return ResponseEntity.ok(ApiResponse.success("No bookings found", List.of()));
        } else {
            // Scoped strictly to authenticated CUSTOMER (exclude unaccepted / failed searches)
            List<Booking> customerBookings = new ArrayList<>(bookingRepository.findAllByCustomerIdOrderByCreatedAtDesc(userDetails.getId()));
            customerBookings.removeIf(b -> "NO_DRIVER_AVAILABLE".equalsIgnoreCase(b.getStatus()) || "SEARCHING_DRIVER".equalsIgnoreCase(b.getStatus()));
            return ResponseEntity.ok(ApiResponse.success("Customer bookings retrieved successfully", customerBookings));
        }
    }

    @GetMapping("/contractor/{contractorId}")
    public ResponseEntity<ApiResponse<List<Booking>>> getBookingsByContractorId(@PathVariable Long contractorId) {
        log.info("REST request to get bookings for contractor ID: {}", contractorId);
        List<Booking> list = bookingRepository.findAllByContractorIdOrderByCreatedAtDesc(contractorId);
        return ResponseEntity.ok(ApiResponse.success("Contractor bookings retrieved successfully", list));
    }

    @GetMapping("/driver/{driverId}")
    public ResponseEntity<ApiResponse<List<Booking>>> getBookingsByDriverId(@PathVariable Long driverId) {
        log.info("REST request to get bookings for driver ID: {}", driverId);
        List<Booking> list = bookingRepository.findAllByDriverIdOrderByCreatedAtDesc(driverId);
        return ResponseEntity.ok(ApiResponse.success("Driver bookings retrieved successfully", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Booking>> getBookingById(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get booking ID {} for user ID {}", id, userDetails != null ? userDetails.getId() : "null");
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        // Ownership and Role validation
        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CUSTOMER && !booking.getCustomerId().equals(userDetails.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to booking"));
            } else if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || (booking.getDriverId() != null && !booking.getDriverId().equals(drvOpt.get().getId()))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to booking"));
                }
            } else if (role == Role.CONTRACTOR) {
                Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty() || (booking.getContractorId() != null && !booking.getContractorId().equals(conOpt.get().getId()))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to booking"));
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success("Booking retrieved successfully", booking));
    }

    /**
     * Task 4: Server-Authoritative Quotation API
     * POST /api/v1/bookings/quote
     */
    @PostMapping("/quote")
    public ResponseEntity<ApiResponse<QuotationResponse>> getQuotation(@RequestBody QuotationRequest request) {
        log.info("REST request to get authoritative quotation: {}", request);
        try {
            QuotationResponse quote = pricingService.generateAuthoritativeQuotation(request);
            return ResponseEntity.ok(ApiResponse.success("Authoritative quotation generated successfully", quote));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Quotation generation failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Failed to generate quotation: " + e.getMessage()));
        }
    }

    /**
     * Task 5: Complete Booking Creation & Confirmation
     * Support POST /api/v1/bookings and POST /api/v1/bookings/confirm
     */
    @PostMapping(value = {"", "/confirm"})
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> confirmBooking(
            @RequestBody Booking bookingRequest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to confirm booking for customer ID: {}", userDetails != null ? userDetails.getId() : "guest");

        if (bookingRequest.getProductId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Product ID is required for booking"));
        }

        Product product = productRepository.findById(bookingRequest.getProductId()).orElse(null);
        if (product == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid vehicle/product ID: " + bookingRequest.getProductId()));
        }

        // Idempotency Validation (Task 5)
        if (bookingRequest.getIdempotencyKey() != null && !bookingRequest.getIdempotencyKey().trim().isEmpty()) {
            Optional<Booking> existing = bookingRepository.findByIdempotencyKey(bookingRequest.getIdempotencyKey().trim());
            if (existing.isPresent()) {
                log.info("Idempotent booking hit for key {}", bookingRequest.getIdempotencyKey());
                return ResponseEntity.ok(ApiResponse.success("Booking already confirmed (idempotent)", existing.get()));
            }
        }

        Long customerId = (userDetails != null && userDetails.getUser() != null)
                ? userDetails.getId()
                : (bookingRequest.getCustomerId() != null ? bookingRequest.getCustomerId() : 13L);
        bookingRequest.setCustomerId(customerId);

        String bType = bookingRequest.getBookingType();
        if (bType == null || bType.trim().isEmpty()) {
            bType = "LOCAL";
            bookingRequest.setBookingType("LOCAL");
        }

        // Validate Scheduled Pickup Date
        if ("SCHEDULED".equalsIgnoreCase(bType)) {
            if (bookingRequest.getScheduledPickupAt() != null && bookingRequest.getScheduledPickupAt().isBefore(LocalDateTime.now())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled pickup time cannot be in the past"));
            }
        }

        // Validate Round-trip return time
        if ("OUTSTATION".equalsIgnoreCase(bType) && "ROUND_TRIP".equalsIgnoreCase(bookingRequest.getOutstationTripType())) {
            if (bookingRequest.getReturnAt() != null && bookingRequest.getScheduledPickupAt() != null
                    && bookingRequest.getReturnAt().isBefore(bookingRequest.getScheduledPickupAt())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Return time cannot be before pickup time for round-trip"));
            }
        }

        // Authoritative Server-Side Price Calculation & Snapshot
        Map<String, Object> pricingCalc = pricingService.calculatePriceAdvanced(
                bookingRequest.getProductId(),
                bookingRequest.getDistanceKm(),
                bookingRequest.getDurationHours(),
                0.0,
                bookingRequest.getBookingType(),
                bookingRequest.getRentalPackage(),
                bookingRequest.getOutstationTripType()
        );

        bookingRequest.setBaseAmount((Double) pricingCalc.getOrDefault("baseAmount", 0.0));
        bookingRequest.setDistanceAmount((Double) pricingCalc.getOrDefault("distanceAmount", 0.0));
        bookingRequest.setTimeAmount((Double) pricingCalc.getOrDefault("timeAmount", 0.0));
        bookingRequest.setWaitingAmount((Double) pricingCalc.getOrDefault("waitingAmount", 0.0));
        bookingRequest.setDriverAmount((Double) pricingCalc.getOrDefault("driverAmount", 0.0));
        bookingRequest.setOperatorAmount((Double) pricingCalc.getOrDefault("operatorAmount", 0.0));
        bookingRequest.setBookingFee((Double) pricingCalc.getOrDefault("bookingFee", 0.0));
        bookingRequest.setTaxAmount((Double) pricingCalc.getOrDefault("taxAmount", 0.0));
        double totalCalculated = (Double) pricingCalc.getOrDefault("totalAmount", 0.0);
        if (totalCalculated <= 0 && bookingRequest.getEstimatedFare() != null && bookingRequest.getEstimatedFare() > 0) {
            totalCalculated = bookingRequest.getEstimatedFare();
        }
        bookingRequest.setTotalAmount(totalCalculated);
        bookingRequest.setEstimatedFare(totalCalculated);

        // Save fare breakdown snapshot as JSON string
        try {
            bookingRequest.setFareBreakdownSnapshot(objectMapper.writeValueAsString(pricingCalc));
        } catch (Exception e) {
            log.warn("Could not serialize fare snapshot: {}", e.getMessage());
        }

        if (bookingRequest.getBookingNumber() == null || bookingRequest.getBookingNumber().trim().isEmpty()) {
            bookingRequest.setBookingNumber("BK-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        }

        // Generate 4-digit ride verification OTP and SHA-256 hash with security parameters
        String plainOtp = String.format("%04d", (int)(Math.random() * 9000) + 1000);
        bookingRequest.setOtp(plainOtp);
        bookingRequest.setRideStartOtpHash(hashOtp(plainOtp));
        bookingRequest.setOtpGeneratedAt(LocalDateTime.now());
        bookingRequest.setOtpExpiresAt(LocalDateTime.now().plusHours(24));
        bookingRequest.setOtpAttempts(0);
        bookingRequest.setOtpMaxAttempts(5);

        // State Machine Initial Status: Customer starts at SEARCHING_DRIVER (Figma Screen 3)
        // If booking is scheduled for the future (more than 5 minutes from now), status is SCHEDULED
        boolean isScheduledForFuture = bookingRequest.getScheduledPickupAt() != null
                && bookingRequest.getScheduledPickupAt().isAfter(LocalDateTime.now().plusMinutes(5));

        if ("SCHEDULED".equalsIgnoreCase(bookingRequest.getBookingType()) || isScheduledForFuture) {
            bookingRequest.setStatus("SCHEDULED");
            bookingRequest.setBookingType("SCHEDULED");
            if (bookingRequest.getPaymentStatus() == null) {
                bookingRequest.setPaymentStatus("PENDING");
            }
        } else {
            bookingRequest.setStatus("SEARCHING_DRIVER");
            if (bookingRequest.getPaymentStatus() == null) {
                bookingRequest.setPaymentStatus("PENDING");
            }
        }

        Booking savedBooking = bookingRepository.save(bookingRequest);

        // Atomically clear customer cart after booking
        try {
            cartRepository.deleteAllByCustomerId(customerId);
        } catch (Exception e) {
            log.warn("Error clearing cart: {}", e.getMessage());
        }

        // Record Audit Event
        recordHistory(savedBooking.getId(), "CREATED", savedBooking.getStatus(), customerId, "CUSTOMER", "Booking created");

        // Broadcast WebSocket Event
        webSocketNotificationService.sendBookingLifecycleEvent(savedBooking.getId(), "BOOKING_CREATED", Map.of(
                "bookingId", savedBooking.getId(),
                "bookingNumber", savedBooking.getBookingNumber(),
                "bookingType", savedBooking.getBookingType(),
                "status", savedBooking.getStatus(),
                "totalAmount", savedBooking.getTotalAmount()
        ));

        // Trigger Auto-Assignment Engine for driver search ONLY for immediate rides
        if ("SEARCHING_DRIVER".equalsIgnoreCase(savedBooking.getStatus())) {
            bookingAssignmentService.startAutoAssignmentProcess(savedBooking.getId());
        } else if ("SCHEDULED".equalsIgnoreCase(savedBooking.getStatus())) {
            bookingSchedulerService.scheduleScheduledBookingDispatch(savedBooking, 15);
        }

        log.info("Booking created successfully. ID: {}, Number: {}, Status: {}, Payment: {}",
                savedBooking.getId(), savedBooking.getBookingNumber(), savedBooking.getStatus(), savedBooking.getPaymentStatus());

        return ResponseEntity.ok(ApiResponse.success("Booking confirmed successfully", savedBooking));
    }

    /**
     * Driver Offer Acceptance Endpoint
     */
    @PostMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<String>> acceptBookingOffer(
            @PathVariable Long id,
            @RequestParam String candidateType,
            @RequestParam Long candidateId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request by {} ID {} to accept booking offer for booking ID {}", candidateType, candidateId, id);

        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || !drvOpt.get().getId().equals(candidateId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized: Candidate ID does not match authenticated driver"));
                }
            } else if (role == Role.CONTRACTOR) {
                Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty() || !conOpt.get().getId().equals(candidateId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized: Candidate ID does not match authenticated contractor"));
                }
            } else if (role != Role.ADMIN) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Only drivers or contractors can accept booking offers"));
            }
        }

        Long userId = userDetails != null ? userDetails.getId() : candidateId;
        boolean success = bookingAssignmentService.acceptBookingOffer(id, candidateType, candidateId, userId);
        if (success) {
            return ResponseEntity.ok(ApiResponse.success("Booking offer accepted successfully", "ASSIGNED"));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("BOOKING_ALREADY_ASSIGNED: This booking offer is expired, driver is already busy, or offer has already been accepted by another candidate"));
        }
    }

    /**
     * Admin Manual Assignment & Reassignment Endpoint (Task 4)
     */
    @PostMapping("/{id}/assign-driver")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> assignDriverManually(
            @PathVariable Long id,
            @RequestParam(required = false) Long contractorId,
            @RequestParam(required = false) Long driverId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to manually assign driver {} / contractor {} to booking ID {}", driverId, contractorId, id);

        if (userDetails == null || userDetails.getUser() == null || (userDetails.getUser().getRole() != Role.ADMIN && userDetails.getUser().getRole() != Role.CONTRACTOR)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Only administrators or contractors can perform manual booking assignment"));
        }

        Long targetContractorId = contractorId;
        Long targetDriverId = driverId;
        if (body != null) {
            if (body.containsKey("contractorId") && body.get("contractorId") != null) {
                try { targetContractorId = Long.valueOf(body.get("contractorId").toString()); } catch (Exception ignored) {}
            }
            if (body.containsKey("driverId") && body.get("driverId") != null) {
                try { targetDriverId = Long.valueOf(body.get("driverId").toString()); } catch (Exception ignored) {}
            }
        }

        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        String currentStatus = booking.getStatus();
        if ("COMPLETED".equalsIgnoreCase(currentStatus) || "CANCELLED".equalsIgnoreCase(currentStatus) || "EXPIRED".equalsIgnoreCase(currentStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Cannot assign driver to a terminal (" + currentStatus + ") booking"));
        }

        // Validate Driver if provided
        Driver driver = null;
        if (targetDriverId != null) {
            driver = driverRepository.findById(targetDriverId).orElse(null);
            if (driver == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Specified driver does not exist"));
            }
            if ("SUSPENDED".equalsIgnoreCase(driver.getStatus()) || "REJECTED".equalsIgnoreCase(driver.getStatus())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error("Driver account is " + driver.getStatus() + " and cannot be assigned"));
            }

            // Check if driver has active overlapping job
            List<String> activeStatuses = List.of("ASSIGNED", "ACCEPTED", "DRIVER_ON_THE_WAY", "ARRIVED", "STARTED", "IN_PROGRESS");
            boolean hasActiveJob = bookingRepository.existsByDriverIdAndStatusIn(targetDriverId, activeStatuses);
            if (hasActiveJob && (booking.getDriverId() == null || !booking.getDriverId().equals(targetDriverId))) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error("Driver already has an active overlapping booking"));
            }

            if (targetContractorId == null && driver.getContractorId() != null) {
                targetContractorId = driver.getContractorId();
            }
        }

        // Validate Contractor if provided
        if (targetContractorId != null) {
            if (!contractorRepository.existsById(targetContractorId)) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Specified contractor does not exist"));
            }
        }

        booking.setDriverId(targetDriverId);
        booking.setContractorId(targetContractorId);
        if (targetDriverId != null) {
            booking.setStatus("ASSIGNED");
        }
        Booking saved = bookingRepository.save(booking);

        // Invalidate active offers on this booking
        List<BookingAssignment> activeOffers = bookingAssignmentRepository.findAllByBookingIdOrderByOfferedAtDesc(id);
        for (BookingAssignment ba : activeOffers) {
            if ("OFFERED".equalsIgnoreCase(ba.getStatus())) {
                ba.setStatus("EXPIRED");
                ba.setRespondedAt(LocalDateTime.now());
                bookingAssignmentRepository.save(ba);
            }
        }

        // Record audit history
        recordHistory(id, currentStatus, saved.getStatus(), userDetails.getId(), "ADMIN", 
                "Manual assignment by Admin: Driver " + targetDriverId + ", Contractor " + targetContractorId);

        // WebSocket notification
        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("bookingId", id);
        wsPayload.put("status", saved.getStatus());
        wsPayload.put("driverId", targetDriverId);
        wsPayload.put("contractorId", targetContractorId);
        webSocketNotificationService.sendBookingLifecycleEvent(id, "DRIVER_ASSIGNED", wsPayload);

        // Push notification to driver if assigned
        if (driver != null && driver.getUserId() != null) {
            try {
                fcmNotificationService.sendBookingAssignedPush(driver.getUserId(), id, saved.getBookingNumber());
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok(ApiResponse.success("Driver assigned successfully to booking", saved));
    }

    /**
     * Driver Offer Rejection Endpoint
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<String>> rejectBookingOffer(
            @PathVariable Long id,
            @RequestParam String candidateType,
            @RequestParam Long candidateId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request by {} ID {} to reject booking offer for booking ID {}", candidateType, candidateId, id);

        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || !drvOpt.get().getId().equals(candidateId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized: Candidate ID does not match authenticated driver"));
                }
            } else if (role == Role.CONTRACTOR) {
                Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty() || !conOpt.get().getId().equals(candidateId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized: Candidate ID does not match authenticated contractor"));
                }
            } else if (role != Role.ADMIN) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Only drivers or contractors can reject booking offers"));
            }
        }

        Long userId = userDetails != null ? userDetails.getId() : candidateId;
        boolean success = bookingAssignmentService.rejectBookingOffer(id, candidateType, candidateId, userId, reason);
        if (success) {
            return ResponseEntity.ok(ApiResponse.success("Booking offer rejected", "REJECTED"));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("No active offer found to reject"));
        }
    }

    /**
     * Customer Driver Tip
     */
    @PostMapping("/{id}/tip")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> addDriverTip(
            @PathVariable Long id,
            @RequestParam Double amount,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to add tip of ₹{} to booking ID {}", amount, id);
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        double tip = (amount != null && amount > 0) ? amount : 0.0;
        booking.setDriverTip(tip);
        double prevTotal = (booking.getTotalAmount() != null) ? booking.getTotalAmount() : 0.0;
        booking.setTotalAmount(prevTotal + tip);

        Booking saved = bookingRepository.save(booking);
        return ResponseEntity.ok(ApiResponse.success("Driver tip added successfully", saved));
    }

    /**
     * Ride Start OTP Verification
     */
    @PostMapping("/{id}/verify-otp")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> verifyOtpAndStart(
            @PathVariable Long id,
            @RequestParam String otp,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to verify OTP for booking ID {}", id);
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        // Driver Identity & Authorization Check
        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || booking.getDriverId() == null || !booking.getDriverId().equals(drvOpt.get().getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Only the assigned driver can verify ride OTP"));
                }
            } else if (role != Role.ADMIN) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized to verify ride OTP"));
            }
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
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("OTP_LOCKED: Maximum OTP verification attempts (" + maxAttempts + ") exceeded. Contact support"));
        }

        String inputHash = hashOtp(otp);
        boolean matches = (booking.getOtp() != null && booking.getOtp().trim().equals(otp.trim()))
                || (booking.getRideStartOtpHash() != null && booking.getRideStartOtpHash().equals(inputHash));

        if (!matches) {
            booking.setOtpAttempts(attempts + 1);
            bookingRepository.save(booking);
            int remaining = maxAttempts - (attempts + 1);
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_OTP: Entered OTP does not match. Remaining attempts: " + Math.max(0, remaining)));
        }

        String prevStatus = booking.getStatus();
        booking.setStatus("STARTED");
        booking.setOtpVerifiedAt(LocalDateTime.now());
        booking.setOtp(null); // One-time use: Clear plaintext OTP to prevent replay or leaks
        booking.setRideStartedAt(LocalDateTime.now());
        Booking saved = bookingRepository.save(booking);

        recordHistory(id, prevStatus, "STARTED", userDetails != null ? userDetails.getId() : null, "DRIVER", "OTP verified and ride started");
        webSocketNotificationService.sendBookingLifecycleEvent(id, "RIDE_STARTED", Map.of("bookingId", id, "status", "STARTED"));

        return ResponseEntity.ok(ApiResponse.success("OTP verified and ride started", saved));
    }

    /**
     * Upload Work Proof / Completion Photo
     */
    @PostMapping("/{id}/proof")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> uploadWorkProof(
            @PathVariable Long id,
            @RequestParam String photoUrl,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to record work proof photo for booking ID {}", id);
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        booking.setWorksitePhotoUrl(photoUrl);
        Booking saved = bookingRepository.save(booking);
        return ResponseEntity.ok(ApiResponse.success("Work proof uploaded successfully", saved));
    }

    /**
     * Task 6: Booking Status Workflow State Machine
     */
    @PostMapping("/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> updateBookingStatus(
            @PathVariable Long id,
            @RequestParam String newStatus,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to update status of booking ID {} to {}", id, newStatus);

        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        String currentStatus = booking.getStatus();
        if ("COMPLETED".equals(currentStatus) || "CANCELLED".equals(currentStatus) || "EXPIRED".equals(currentStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("Cannot change status of a terminal " + currentStatus + " booking"));
        }

        // Strict Server-Enforced State Machine (Task 6)
        boolean validTransition = false;
        switch (currentStatus) {
            case "PENDING":
                validTransition = List.of("CONFIRMED", "SCHEDULED", "CANCELLED", "REJECTED", "EXPIRED").contains(newStatus);
                break;
            case "SCHEDULED":
                validTransition = List.of("CONFIRMED", "SEARCHING_DRIVER", "ASSIGNED", "CANCELLED").contains(newStatus);
                break;
            case "CONFIRMED":
                validTransition = List.of("SEARCHING_DRIVER", "ASSIGNED", "CANCELLED").contains(newStatus);
                break;
            case "SEARCHING_DRIVER":
                validTransition = List.of("ASSIGNED", "NO_DRIVER_AVAILABLE", "CANCELLED", "EXPIRED").contains(newStatus);
                break;
            case "ASSIGNED":
                validTransition = List.of("DRIVER_ON_THE_WAY", "ACCEPTED", "REJECTED", "CANCELLED").contains(newStatus);
                break;
            case "ACCEPTED":
                validTransition = List.of("DRIVER_ON_THE_WAY", "ARRIVED", "STARTED", "CANCELLED").contains(newStatus);
                break;
            case "DRIVER_ON_THE_WAY":
                validTransition = List.of("ARRIVED", "STARTED", "CANCELLED").contains(newStatus);
                break;
            case "ARRIVED":
                validTransition = List.of("STARTED", "CANCELLED").contains(newStatus);
                break;
            case "STARTED":
                validTransition = List.of("IN_PROGRESS", "COMPLETED", "CANCELLED").contains(newStatus);
                break;
            case "IN_PROGRESS":
                validTransition = List.of("COMPLETED", "CANCELLED").contains(newStatus);
                break;
            default:
                validTransition = false;
                break;
        }

        if (!validTransition) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Invalid state transition from " + currentStatus + " to " + newStatus));
        }

        booking.setStatus(newStatus);

        // Record Timestamps & Reason
        if ("ARRIVED".equalsIgnoreCase(newStatus)) {
            booking.setDriverArrivedAt(LocalDateTime.now());
        } else if ("STARTED".equalsIgnoreCase(newStatus)) {
            booking.setRideStartedAt(LocalDateTime.now());
        } else if ("COMPLETED".equalsIgnoreCase(newStatus)) {
            booking.setRideCompletedAt(LocalDateTime.now());
            if (booking.getFinalFare() == null || booking.getFinalFare() <= 0.0) {
                booking.setFinalFare(booking.getTotalAmount());
            }
        } else if ("CANCELLED".equalsIgnoreCase(newStatus)) {
            booking.setCancellationReason(reason != null ? reason : "Cancelled by user");
            bookingSchedulerService.cancelScheduledBookingJob(booking.getId());
        }

        Booking saved = bookingRepository.save(booking);

        Long actorId = userDetails != null ? userDetails.getId() : null;
        String actorRole = userDetails != null && userDetails.getUser() != null ? userDetails.getUser().getRole().name() : "USER";
        recordHistory(id, currentStatus, newStatus, actorId, actorRole, reason);

        webSocketNotificationService.sendBookingLifecycleEvent(id, newStatus, Map.of(
                "bookingId", id,
                "status", newStatus,
                "reason", reason != null ? reason : ""
        ));

        return ResponseEntity.ok(ApiResponse.success("Booking status updated to " + newStatus, saved));
    }

    /**
     * Customer Booking Cancellation
     */
    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<ApiResponse<Booking>> cancelBooking(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to cancel booking ID {}", id);

        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking != null && "SEARCHING_DRIVER".equalsIgnoreCase(booking.getStatus()) && booking.getDriverId() == null) {
            log.info("Cancelling unaccepted search booking ID {}. Removing temporary search record.", id);
            try {
                bookingAssignmentRepository.deleteAllByBookingId(id);
                bookingEventHistoryRepository.deleteAllByBookingId(id);
                bookingRepository.deleteById(id);
            } catch (Exception e) {
                log.warn("Failed to delete unaccepted search booking ID {}: {}", id, e.getMessage());
            }
            webSocketNotificationService.sendBookingLifecycleEvent(id, "CANCELLED", Map.of(
                    "bookingId", id,
                    "status", "CANCELLED",
                    "reason", reason != null ? reason : "Cancelled while searching"
            ));
            return ResponseEntity.ok(ApiResponse.success("Booking search cancelled", booking));
        }

        return updateBookingStatus(id, "CANCELLED", reason != null ? reason : "Cancelled by customer", userDetails);
    }

    /**
     * Live Tracking Information
     */
    @GetMapping("/{id}/track")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLiveTracking(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CUSTOMER && !booking.getCustomerId().equals(userDetails.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to live tracking"));
            } else if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || booking.getDriverId() == null || !booking.getDriverId().equals(drvOpt.get().getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to live tracking"));
                }
            } else if (role == Role.CONTRACTOR) {
                Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty() || booking.getContractorId() == null || !booking.getContractorId().equals(conOpt.get().getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to live tracking"));
                }
            }
        }

        Map<String, Object> tracking = new HashMap<>();
        tracking.put("bookingId", booking.getId());
        tracking.put("bookingNumber", booking.getBookingNumber());
        tracking.put("status", booking.getStatus());
        tracking.put("bookingType", booking.getBookingType());
        tracking.put("scheduledPickupAt", booking.getScheduledPickupAt());
        tracking.put("pickupAddressText", booking.getPickupAddressText());
        tracking.put("destinationAddressText", booking.getDestinationAddressText());
        tracking.put("stopAddressText", booking.getStopAddressText());
        tracking.put("pickupLatitude", booking.getPickupLatitude());
        tracking.put("pickupLongitude", booking.getPickupLongitude());
        tracking.put("dropLatitude", booking.getDropLatitude());
        tracking.put("dropLongitude", booking.getDropLongitude());
        tracking.put("stopLatitude", booking.getStopLatitude());
        tracking.put("stopLongitude", booking.getStopLongitude());
        tracking.put("estimatedFare", booking.getEstimatedFare());
        tracking.put("totalAmount", booking.getTotalAmount());
        tracking.put("paymentMethod", booking.getPaymentMethod());
        tracking.put("paymentStatus", booking.getPaymentStatus());
        tracking.put("otp", booking.getOtp());

        // Check if scheduled ride live tracking is active
        boolean isScheduledFuture = booking.getScheduledPickupAt() != null
                && booking.getScheduledPickupAt().isAfter(LocalDateTime.now().plusMinutes(30));
        tracking.put("liveTrackingEnabled", !isScheduledFuture);
        if (isScheduledFuture) {
            tracking.put("scheduledNotice", "Driver live tracking will be enabled on your scheduled date (10-15 min before pickup).");
        }

        if (booking.getDriverId() != null) {
            driverRepository.findById(booking.getDriverId()).ifPresent(d -> {
                tracking.put("driverId", d.getId());
                tracking.put("driverName", d.getFullName());
                tracking.put("driverPhone", d.getPhone());
                tracking.put("driverLatitude", d.getLatitude());
                tracking.put("driverLongitude", d.getLongitude());
                tracking.put("driverRating", d.getRating());
                tracking.put("vehicleType", d.getSelectedVehicleType());
                tracking.put("locationUpdatedAt", d.getLocationUpdatedAt());

                // Driver movement & stop indicator (which stop driver is coming to)
                String st = (booking.getStatus() != null ? booking.getStatus() : "").toUpperCase();
                if ("STARTED".equals(st) || "IN_PROGRESS".equals(st)) {
                    if (booking.getStopAddressText() != null && !booking.getStopAddressText().trim().isEmpty()) {
                        tracking.put("currentHeadingStop", "INTERMEDIATE_STOP");
                        tracking.put("currentStopName", booking.getStopAddressText());
                        tracking.put("driverHeadingMessage", "Driver is heading to Stop: " + booking.getStopAddressText());
                    } else {
                        tracking.put("currentHeadingStop", "DESTINATION");
                        tracking.put("currentStopName", booking.getDestinationAddressText());
                        tracking.put("driverHeadingMessage", "Driver is heading to Destination");
                    }
                } else if ("ACCEPTED".equals(st) || "DRIVER_ON_THE_WAY".equals(st)) {
                    tracking.put("currentHeadingStop", "PICKUP");
                    tracking.put("currentStopName", booking.getPickupAddressText());
                    tracking.put("driverHeadingMessage", "Driver is heading to Pickup location");
                } else if ("ARRIVED".equals(st)) {
                    tracking.put("currentHeadingStop", "PICKUP_ARRIVED");
                    tracking.put("currentStopName", booking.getPickupAddressText());
                    tracking.put("driverHeadingMessage", "Driver has arrived at Pickup location");
                }
            });
        }

        return ResponseEntity.ok(ApiResponse.success("Live tracking retrieved", tracking));
    }

    /**
     * Allocation Progress
     */
    @GetMapping("/{id}/allocation-progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllocationProgress(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CUSTOMER && !booking.getCustomerId().equals(userDetails.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to allocation progress"));
            }
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Authentication required to view allocation progress"));
        }

        List<BookingAssignment> assignments = bookingAssignmentRepository.findAllByBookingIdOrderByOfferedAtDesc(id);

        Map<String, Object> progress = new HashMap<>();
        progress.put("bookingId", booking.getId());
        progress.put("status", booking.getStatus());
        progress.put("totalOffersMade", assignments.size());
        progress.put("activeOffers", assignments.stream().filter(a -> "OFFERED".equals(a.getStatus())).count());
        progress.put("driverAssigned", booking.getDriverId() != null);
        progress.put("assignedDriverId", booking.getDriverId());
        progress.put("recentAssignments", assignments);

        return ResponseEntity.ok(ApiResponse.success("Allocation progress retrieved", progress));
    }

    /**
     * Booking Invoice / Receipt
     */
    @GetMapping("/{id}/invoice")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInvoice(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.notFound().build();
        }

        if (userDetails != null && userDetails.getUser() != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CUSTOMER && !booking.getCustomerId().equals(userDetails.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to invoice"));
            } else if (role == Role.DRIVER) {
                Optional<Driver> drvOpt = driverRepository.findByUserId(userDetails.getId());
                if (drvOpt.isEmpty() || booking.getDriverId() == null || !booking.getDriverId().equals(drvOpt.get().getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to invoice"));
                }
            } else if (role == Role.CONTRACTOR) {
                Optional<Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty() || booking.getContractorId() == null || !booking.getContractorId().equals(conOpt.get().getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized access to invoice"));
                }
            }
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Authentication required to access invoice"));
        }

        Map<String, Object> invoice = new HashMap<>();
        invoice.put("bookingId", booking.getId());
        invoice.put("bookingNumber", booking.getBookingNumber());
        invoice.put("invoiceNumber", "INV-" + (50000 + booking.getId()));
        invoice.put("date", booking.getCreatedAt());
        invoice.put("customerId", booking.getCustomerId());
        invoice.put("bookingType", booking.getBookingType());
        invoice.put("pickupAddress", booking.getPickupAddressText());
        invoice.put("destinationAddress", booking.getDestinationAddressText());
        invoice.put("distanceKm", booking.getDistanceKm());
        invoice.put("durationHours", booking.getDurationHours());
        invoice.put("baseAmount", booking.getBaseAmount());
        invoice.put("distanceAmount", booking.getDistanceAmount());
        invoice.put("timeAmount", booking.getTimeAmount());
        invoice.put("driverAmount", booking.getDriverAmount());
        invoice.put("waitingAmount", booking.getWaitingAmount());
        invoice.put("bookingFee", booking.getBookingFee());
        invoice.put("discountAmount", booking.getDiscountAmount());
        invoice.put("taxAmount", booking.getTaxAmount());
        invoice.put("driverTip", booking.getDriverTip());
        invoice.put("totalAmount", booking.getTotalAmount());
        invoice.put("finalFare", booking.getFinalFare() != null ? booking.getFinalFare() : booking.getTotalAmount());
        invoice.put("paymentMethod", booking.getPaymentMethod());
        invoice.put("paymentStatus", booking.getPaymentStatus());

        return ResponseEntity.ok(ApiResponse.success("Invoice generated successfully", invoice));
    }

    /**
     * Booking Lifecycle Audit Timeline (Task 4)
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<BookingEventHistory>>> getBookingLifecycleTimeline(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get lifecycle audit timeline for booking ID {}", id);
        List<BookingEventHistory> timeline = bookingEventHistoryRepository.findAllByBookingIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(ApiResponse.success("Booking lifecycle timeline retrieved successfully", timeline));
    }

    private void recordHistory(Long bookingId, String fromStatus, String toStatus, Long actorUserId, String actorRole, String reason) {
        try {
            bookingEventHistoryRepository.save(BookingEventHistory.builder()
                    .bookingId(bookingId)
                    .fromStatus(fromStatus)
                    .toStatus(toStatus)
                    .actorUserId(actorUserId)
                    .actorRole(actorRole)
                    .reason(reason)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record event history: {}", e.getMessage());
        }
    }

    @PostMapping("/{id}/rate")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> rateDriver(
            @PathVariable Long id,
            @RequestBody Map<String, Object> ratingPayload) {
        log.info("REST request to rate driver for booking ID {}", id);
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Booking not found"));
        }

        double parsedScore = 5.0;
        if (ratingPayload.containsKey("score") && ratingPayload.get("score") != null) {
            parsedScore = Double.parseDouble(ratingPayload.get("score").toString());
        } else if (ratingPayload.containsKey("rating") && ratingPayload.get("rating") != null) {
            parsedScore = Double.parseDouble(ratingPayload.get("rating").toString());
        }
        final double score = parsedScore;

        String comment = ratingPayload.containsKey("comment") ? (String) ratingPayload.get("comment") : "";
        if (comment == null || comment.isEmpty()) {
            comment = ratingPayload.containsKey("notes") ? (String) ratingPayload.get("notes") : "";
        }

        if (booking.getDriverId() != null) {
            driverRepository.findById(booking.getDriverId()).ifPresent(driver -> {
                double currentRating = driver.getRating() != null ? driver.getRating() : 5.0;
                int totalRatings = driver.getTotalJobs() != null && driver.getTotalJobs() > 0 ? driver.getTotalJobs() : 1;
                double newRating = Math.round(((currentRating * totalRatings + score) / (totalRatings + 1)) * 10.0) / 10.0;
                driver.setRating(newRating);
                driverRepository.save(driver);
            });
        }

        if (comment != null && !comment.trim().isEmpty()) {
            booking.setNotes("Customer Rating: " + score + "★ - " + comment);
            bookingRepository.save(booking);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", id);
        result.put("rating", score);
        result.put("comment", comment);
        result.put("status", "SUCCESS");

        return ResponseEntity.ok(ApiResponse.success("Thank you for your rating!", result));
    }

    private static String hashOtp(String otp) {
        if (otp == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(otp.trim().getBytes(StandardCharsets.UTF_8));
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
