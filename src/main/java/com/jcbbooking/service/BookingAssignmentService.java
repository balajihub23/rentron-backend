package com.jcbbooking.service;

import com.jcbbooking.model.*;
import com.jcbbooking.repository.*;
import com.jcbbooking.strategy.ProductAvailabilityStrategy;
import com.jcbbooking.strategy.ProductAvailabilityStrategyFactory;
import com.jcbbooking.util.HaversineDistanceUtil;
import com.jcbbooking.websocket.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAssignmentService {

    private final BookingRepository bookingRepository;
    private final AddressRepository addressRepository;
    private final DriverRepository driverRepository;
    private final ContractorRepository contractorRepository;
    private final BookingSettingRepository bookingSettingRepository;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final BookingEventHistoryRepository bookingEventHistoryRepository;
    private final ProductAvailabilityStrategyFactory strategyFactory;
    private final WebSocketNotificationService webSocketNotificationService;
    private final ProductRepository productRepository;
    private final FcmNotificationService fcmNotificationService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public BookingSetting getOrInitSettings() {
        return bookingSettingRepository.findBySettingKey("DEFAULT_SETTINGS")
                .orElseGet(() -> {
                    BookingSetting def = BookingSetting.builder()
                            .settingKey("DEFAULT_SETTINGS")
                            .currency("INR")
                            .assignmentType("AUTO")
                            .initialRadiusKm(5.0)
                            .radiusIncrementKm(5.0)
                            .maxRadiusKm(20.0)
                            .assignmentTimeoutSeconds(30)
                            .maxLocationAgeSeconds(300)
                            .dispatchLeadTimeMinutes(30)
                            .notifyAdminWhenUnassigned(true)
                            .build();
                    return bookingSettingRepository.save(def);
                });
    }

    @Async
    public void startAutoAssignmentProcess(Long bookingId) {
        log.info("Starting Auto-Assignment engine for booking ID {}", bookingId);

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            log.error("Booking ID {} not found for auto assignment", bookingId);
            return;
        }

        BookingSetting settings = getOrInitSettings();
        if (!"AUTO".equalsIgnoreCase(settings.getAssignmentType())) {
            log.info("Assignment type is MANUAL for booking ID {}. Skipping auto-assignment.", bookingId);
            return;
        }

        // Idempotency: Update status to SEARCHING_DRIVER if currently CONFIRMED or PENDING
        if ("CONFIRMED".equalsIgnoreCase(booking.getStatus()) || "PENDING".equalsIgnoreCase(booking.getStatus())) {
            booking.setStatus("SEARCHING_DRIVER");
            bookingRepository.save(booking);

            recordEventHistory(bookingId, "CONFIRMED", "SEARCHING_DRIVER", booking.getCustomerId(), "CUSTOMER", "Auto-assignment initiated");
            webSocketNotificationService.sendBookingLifecycleEvent(bookingId, "SEARCHING_DRIVER", Map.of(
                    "bookingId", bookingId,
                    "status", "SEARCHING_DRIVER",
                    "initialRadiusKm", settings.getInitialRadiusKm()
            ));
        }

        double pickupLat = 0.0;
        double pickupLon = 0.0;

        if (booking.getPickupLatitude() != null && booking.getPickupLongitude() != null) {
            pickupLat = booking.getPickupLatitude();
            pickupLon = booking.getPickupLongitude();
        } else if (booking.getPickupAddressId() != null) {
            Address pickupAddr = addressRepository.findById(booking.getPickupAddressId()).orElse(null);
            if (pickupAddr != null && pickupAddr.getLatitude() != null && pickupAddr.getLongitude() != null) {
                pickupLat = pickupAddr.getLatitude();
                pickupLon = pickupAddr.getLongitude();
            }
        }

        if (pickupLat == 0.0 && pickupLon == 0.0) {
            log.warn("Cannot dispatch booking ID {} with missing or 0,0 pickup coordinates", bookingId);
            return;
        }

        executeLayerSearch(bookingId, 1, 0.0, settings.getInitialRadiusKm(), pickupLat, pickupLon, settings);
    }

    private void executeLayerSearch(Long bookingId, int layerIndex, double minRadiusKm, double maxLayerRadiusKm,
                                    double pickupLat, double pickupLon, BookingSetting settings) {

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || "ASSIGNED".equalsIgnoreCase(booking.getStatus()) || "ACCEPTED".equalsIgnoreCase(booking.getStatus())
                || "CANCELLED".equalsIgnoreCase(booking.getStatus()) || "COMPLETED".equalsIgnoreCase(booking.getStatus())
                || "DRIVER_ON_THE_WAY".equalsIgnoreCase(booking.getStatus()) || "ARRIVED".equalsIgnoreCase(booking.getStatus())) {
            log.info("Booking ID {} is already terminal or assigned ({})", bookingId, booking != null ? booking.getStatus() : "NULL");
            return;
        }

        log.info("Executing Layer {} Search for Booking ID {} in Radius Range [{} KM - {} KM]",
                layerIndex, bookingId, minRadiusKm, maxLayerRadiusKm);

        ProductAvailabilityStrategy strategy = strategyFactory.getStrategy(booking.getProductId());

        // Find Candidates (Drivers & Contractors) with strict eligibility
        List<CandidateWrapper> candidates = findCandidatesInRadius(booking, pickupLat, pickupLon, minRadiusKm, maxLayerRadiusKm, settings, strategy);

        if (!candidates.isEmpty()) {
            log.info("Found {} eligible candidates in Layer {} for Booking ID {}", candidates.size(), layerIndex, bookingId);

            // Log Assignment Offers & Broadcast WebSocket Notifications
            for (CandidateWrapper cand : candidates) {
                BookingAssignment assignment = BookingAssignment.builder()
                        .bookingId(bookingId)
                        .layerIndex(layerIndex)
                        .radiusKm(maxLayerRadiusKm)
                        .candidateType(cand.type)
                        .candidateId(cand.id)
                        .candidateUserId(cand.userId)
                        .distanceKm(cand.distanceKm)
                        .status("OFFERED")
                        .build();
                bookingAssignmentRepository.save(assignment);

                Map<String, Object> offerPayload = new HashMap<>();
                offerPayload.put("type", "NEW_BOOKING");
                offerPayload.put("bookingId", bookingId);
                offerPayload.put("bookingNumber", booking.getBookingNumber());
                offerPayload.put("layerIndex", layerIndex);
                offerPayload.put("radiusKm", maxLayerRadiusKm);
                offerPayload.put("distanceKm", cand.distanceKm);
                offerPayload.put("totalAmount", booking.getTotalAmount() != null ? booking.getTotalAmount() : booking.getEstimatedFare());
                offerPayload.put("expiresInSeconds", settings.getAssignmentTimeoutSeconds());
                offerPayload.put("pickupAddress", booking.getPickupAddressText());
                offerPayload.put("stopAddress", booking.getStopAddressText());
                offerPayload.put("destinationAddress", booking.getDestinationAddressText());
                String prodName = "Vehicle";
                String vType = "STANDARD";
                if (booking.getProductId() != null) {
                    Product p = productRepository.findById(booking.getProductId()).orElse(null);
                    if (p != null) {
                        prodName = p.getName();
                        vType = p.getProductType();
                    }
                }
                offerPayload.put("productName", prodName);
                offerPayload.put("vehicleType", vType);
                offerPayload.put("bookingType", booking.getBookingType());
                offerPayload.put("driverAmount", booking.getDriverAmount() != null ? booking.getDriverAmount() : (booking.getTotalAmount() != null ? booking.getTotalAmount() * 0.8 : 0.0));
                offerPayload.put("candidateId", cand.id);
                offerPayload.put("candidateType", cand.type);
                if ("DRIVER".equalsIgnoreCase(cand.type)) {
                    offerPayload.put("driverId", cand.id);
                }
                offerPayload.put("userId", cand.userId);
                offerPayload.put("offeredAt", LocalDateTime.now().toString());

                webSocketNotificationService.sendBookingOfferToUser(cand.userId, offerPayload);
                if (cand.userId != null) {
                    try {
                        fcmNotificationService.sendBookingOfferPush(
                                cand.userId,
                                bookingId,
                                booking.getBookingNumber(),
                                booking.getPickupAddressText(),
                                booking.getTotalAmount() != null ? booking.getTotalAmount() : booking.getEstimatedFare()
                        );
                    } catch (Exception e) {
                        log.warn("FCM push offer failed for user {}: {}", cand.userId, e.getMessage());
                    }
                }
            }

            // Schedule timeout before progressing to next layer
            int timeoutSec = settings.getAssignmentTimeoutSeconds();
            double nextMinRadius = maxLayerRadiusKm;
            double nextMaxRadius = maxLayerRadiusKm + settings.getRadiusIncrementKm();

            scheduler.schedule(() -> {
                Booking freshBooking = bookingRepository.findById(bookingId).orElse(null);
                // FIXED precedence bug: grouping with parentheses
                if (freshBooking != null && ("PENDING".equalsIgnoreCase(freshBooking.getStatus())
                        || "CONFIRMED".equalsIgnoreCase(freshBooking.getStatus())
                        || "SEARCHING_DRIVER".equalsIgnoreCase(freshBooking.getStatus()))) {

                    if (nextMinRadius < settings.getMaxRadiusKm()) {
                        executeLayerSearch(bookingId, layerIndex + 1, nextMinRadius, Math.min(nextMaxRadius, settings.getMaxRadiusKm()), pickupLat, pickupLon, settings);
                    } else {
                        handleAdminFallback(freshBooking, settings);
                    }
                }
            }, timeoutSec, TimeUnit.SECONDS);

        } else {
            log.info("No eligible candidates found in Layer {} for Booking ID {}. Checking next layer...", layerIndex, bookingId);
            double nextMinRadius = maxLayerRadiusKm;
            double nextMaxRadius = maxLayerRadiusKm + settings.getRadiusIncrementKm();

            if (nextMinRadius < settings.getMaxRadiusKm()) {
                executeLayerSearch(bookingId, layerIndex + 1, nextMinRadius, Math.min(nextMaxRadius, settings.getMaxRadiusKm()), pickupLat, pickupLon, settings);
            } else {
                handleAdminFallback(booking, settings);
            }
        }
    }

    private void handleAdminFallback(Booking booking, BookingSetting settings) {
        Long bookingId = booking.getId();
        Map<String, Object> alert = new HashMap<>();
        alert.put("bookingId", bookingId);
        alert.put("bookingNumber", booking.getBookingNumber());
        alert.put("message", "No driver accepted the booking within maximum radius of " + settings.getMaxRadiusKm() + " KM.");
        alert.put("maxRadiusKm", settings.getMaxRadiusKm());

        webSocketNotificationService.sendAdminAssignmentFailedNotification(alert);
        webSocketNotificationService.sendBookingLifecycleEvent(bookingId, "NO_DRIVER_AVAILABLE", alert);

        try {
            bookingAssignmentRepository.deleteAllByBookingId(bookingId);
            bookingEventHistoryRepository.deleteAllByBookingId(bookingId);
            bookingRepository.deleteById(bookingId);
            log.info("Cleaned up unaccepted booking request ID {} since no driver accepted", bookingId);
        } catch (Exception e) {
            log.warn("Failed to delete unaccepted booking ID {}: {}", bookingId, e.getMessage());
            booking.setStatus("NO_DRIVER_AVAILABLE");
            bookingRepository.save(booking);
        }
    }

    /**
     * Concurrency-safe atomic driver acceptance.
     */
    @Transactional
    public boolean acceptBookingOffer(Long bookingId, String candidateType, Long candidateId, Long userId) {
        log.info("Candidate {} ID {} accepting booking offer for booking ID {}", candidateType, candidateId, bookingId);

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            throw new IllegalArgumentException("Booking not found");
        }

        // Verify active offer existence
        Optional<BookingAssignment> myAssignmentOpt = bookingAssignmentRepository
                .findFirstByBookingIdAndCandidateTypeAndCandidateIdAndStatus(bookingId, candidateType, candidateId, "OFFERED");

        if (myAssignmentOpt.isEmpty()) {
            log.warn("Acceptance rejected: no active OFFERED record for candidate {} ID {} on booking {}", candidateType, candidateId, bookingId);
            return false;
        }

        BookingAssignment myAssignment = myAssignmentOpt.get();
        BookingSetting settings = getOrInitSettings();

        // Check if offer expired based on timeout
        if (myAssignment.getOfferedAt() != null &&
                myAssignment.getOfferedAt().plusSeconds(settings.getAssignmentTimeoutSeconds() + 10).isBefore(LocalDateTime.now())) {
            log.warn("Acceptance rejected: offer has timed out for candidate {} ID {} on booking {}", candidateType, candidateId, bookingId);
            myAssignment.setStatus("EXPIRED");
            myAssignment.setRespondedAt(LocalDateTime.now());
            bookingAssignmentRepository.save(myAssignment);
            return false;
        }

        // Validate candidate ownership if caller userId is provided
        if (userId != null) {
            if ("DRIVER".equalsIgnoreCase(candidateType)) {
                Driver drv = driverRepository.findById(candidateId).orElse(null);
                if (drv != null && drv.getUserId() != null && !drv.getUserId().equals(userId)) {
                    log.warn("Acceptance rejected: Authenticated user {} does not own driver {}", userId, candidateId);
                    return false;
                }
            } else if ("CONTRACTOR".equalsIgnoreCase(candidateType)) {
                Contractor con = contractorRepository.findById(candidateId).orElse(null);
                if (con != null && con.getUserId() != null && !con.getUserId().equals(userId)) {
                    log.warn("Acceptance rejected: Authenticated user {} does not own contractor {}", userId, candidateId);
                    return false;
                }
            }
        }

        // Determine driver & contractor IDs
        Long targetDriverId = "DRIVER".equalsIgnoreCase(candidateType) ? candidateId : null;
        Long targetContractorId = "CONTRACTOR".equalsIgnoreCase(candidateType) ? candidateId : null;
        if (targetDriverId != null) {
            Driver driver = driverRepository.findById(targetDriverId).orElse(null);
            if (driver != null && driver.getContractorId() != null) {
                targetContractorId = driver.getContractorId();
            }
        }

        // Prevent one driver from accepting overlapping active bookings
        if (targetDriverId != null) {
            List<String> activeDriverStatuses = List.of("ASSIGNED", "ACCEPTED", "DRIVER_ON_THE_WAY", "ARRIVED", "STARTED", "IN_PROGRESS");
            if (bookingRepository.existsByDriverIdAndStatusIn(targetDriverId, activeDriverStatuses)) {
                log.warn("Acceptance rejected: Driver {} already has an active overlapping booking", targetDriverId);
                myAssignment.setStatus("REJECTED");
                myAssignment.setRejectionReason("DRIVER_BUSY_WITH_ANOTHER_BOOKING");
                myAssignment.setRespondedAt(LocalDateTime.now());
                bookingAssignmentRepository.save(myAssignment);
                return false;
            }
        }

        // Perform Concurrency-Safe Atomic Conditional Database Update
        List<String> acceptableStatuses = List.of("PENDING", "CONFIRMED", "SEARCHING_DRIVER");
        int updated = bookingRepository.assignDriverAtomically(
                bookingId, targetDriverId, targetContractorId, "ASSIGNED", acceptableStatuses
        );

        if (updated == 0) {
            log.warn("Atomic conditional update failed for booking ID {} - already assigned or invalid status", bookingId);
            myAssignment.setStatus("EXPIRED");
            myAssignment.setRespondedAt(LocalDateTime.now());
            bookingAssignmentRepository.save(myAssignment);
            return false;
        }

        // Mark candidate's offer as ACCEPTED
        myAssignment.setStatus("ACCEPTED");
        myAssignment.setRespondedAt(LocalDateTime.now());
        bookingAssignmentRepository.save(myAssignment);

        // Record Audit Event
        recordEventHistory(bookingId, booking.getStatus(), "ASSIGNED", userId, candidateType, "Offer accepted by " + candidateType);

        // Invalidate remaining offers & notify candidates
        List<BookingAssignment> allAssignments = bookingAssignmentRepository.findAllByBookingIdOrderByOfferedAtDesc(bookingId);
        for (BookingAssignment ba : allAssignments) {
            if ("OFFERED".equalsIgnoreCase(ba.getStatus())) {
                ba.setStatus("EXPIRED");
                ba.setRespondedAt(LocalDateTime.now());
                bookingAssignmentRepository.save(ba);

                if (ba.getCandidateUserId() != null && !ba.getCandidateUserId().equals(userId)) {
                    Map<String, Object> cancelMsg = new HashMap<>();
                    cancelMsg.put("type", "BOOKING_ALREADY_ASSIGNED");
                    cancelMsg.put("bookingId", bookingId);
                    cancelMsg.put("message", "This booking has already been assigned to another candidate.");
                    webSocketNotificationService.sendOfferCancelledToUser(ba.getCandidateUserId(), cancelMsg);
                    try {
                        fcmNotificationService.sendBookingCancelledPush(ba.getCandidateUserId(), bookingId, booking.getBookingNumber(), "This booking has been assigned to another driver.");
                    } catch (Exception ignored) {}
                }
            }
        }

        // Notify Customer & Observers via WebSocket
        Map<String, Object> assignedPayload = new HashMap<>();
        assignedPayload.put("bookingId", bookingId);
        assignedPayload.put("status", "ASSIGNED");
        assignedPayload.put("driverId", targetDriverId);
        assignedPayload.put("contractorId", targetContractorId);
        webSocketNotificationService.sendBookingLifecycleEvent(bookingId, "DRIVER_ASSIGNED", assignedPayload);

        if (userId != null) {
            try {
                fcmNotificationService.sendBookingAssignedPush(userId, bookingId, booking.getBookingNumber());
            } catch (Exception ignored) {}
        }

        log.info("Booking ID {} successfully ASSIGNED to {} ID {}", bookingId, candidateType, candidateId);
        return true;
    }

    /**
     * Driver or Contractor rejects an offer.
     */
    @Transactional
    public boolean rejectBookingOffer(Long bookingId, String candidateType, Long candidateId, Long userId, String reason) {
        log.info("Candidate {} ID {} rejecting offer for booking ID {}", candidateType, candidateId, bookingId);

        Optional<BookingAssignment> myAssignmentOpt = bookingAssignmentRepository
                .findFirstByBookingIdAndCandidateTypeAndCandidateIdAndStatus(bookingId, candidateType, candidateId, "OFFERED");

        if (myAssignmentOpt.isPresent()) {
            BookingAssignment assignment = myAssignmentOpt.get();
            assignment.setStatus("REJECTED");
            assignment.setRejectionReason(reason != null ? reason : "Declined by driver");
            assignment.setRespondedAt(LocalDateTime.now());
            bookingAssignmentRepository.save(assignment);

            recordEventHistory(bookingId, "SEARCHING_DRIVER", "OFFER_REJECTED", userId, candidateType, "Offer rejected: " + reason);
            return true;
        }

        return false;
    }

    /**
     * Periodic background durable scheduler for scheduled bookings (Task 8).
     * Runs every 60 seconds to scan and dispatch scheduled rides when lead time is reached.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void scanAndDispatchScheduledBookings() {
        BookingSetting settings = getOrInitSettings();
        int leadTimeMinutes = settings.getDispatchLeadTimeMinutes() != null ? settings.getDispatchLeadTimeMinutes() : 30;
        LocalDateTime threshold = LocalDateTime.now().plusMinutes(leadTimeMinutes);

        List<Booking> scheduledToDispatch = bookingRepository.findAllByStatusAndScheduledPickupAtLessThanEqual("SCHEDULED", threshold);
        if (!scheduledToDispatch.isEmpty()) {
            log.info("Found {} scheduled bookings ready for dispatch (lead time: {} mins)", scheduledToDispatch.size(), leadTimeMinutes);
            for (Booking b : scheduledToDispatch) {
                // Multi-Instance Concurrency Safety: Atomic conditional update
                int transitioned = bookingRepository.transitionStatusIfMatches(b.getId(), "SEARCHING_DRIVER", "SCHEDULED");
                if (transitioned > 0) {
                    recordEventHistory(b.getId(), "SCHEDULED", "SEARCHING_DRIVER", null, "SYSTEM", "Scheduled pickup lead time reached");
                    startAutoAssignmentProcess(b.getId());
                } else {
                    log.info("Scheduled booking ID {} was already claimed by another instance", b.getId());
                }
            }
        }
    }

    private List<CandidateWrapper> findCandidatesInRadius(Booking booking, double pickupLat, double pickupLon,
                                                          double minRadiusKm, double maxRadiusKm, BookingSetting settings,
                                                          ProductAvailabilityStrategy strategy) {

        List<CandidateWrapper> candidates = new ArrayList<>();
        LocalDateTime staleThreshold = LocalDateTime.now().minusSeconds(settings.getMaxLocationAgeSeconds());

        String requestedVehicleType = getRequestedVehicleType(booking != null ? booking.getProductId() : null);

        // 1. Search Drivers with Full Eligibility Verification
        List<Driver> activeDrivers = driverRepository.findAllByStatus("ACTIVE");
        for (Driver d : activeDrivers) {
            // Must have valid coordinates
            if (d.getLatitude() != null && d.getLongitude() != null) {
                // Must be online
                if (Boolean.TRUE.equals(d.getIsOnline())) {
                    // Vehicle type matching
                    if (isVehicleTypeMatch(d.getSelectedVehicleType(), requestedVehicleType)) {
                        // Location freshness: active online drivers are always eligible regardless of location age
                        if (Boolean.TRUE.equals(d.getIsOnline()) || d.getLocationUpdatedAt() == null || d.getLocationUpdatedAt().isAfter(staleThreshold)) {
                            // Check conflicting active booking
                            boolean hasActiveRide = bookingRepository.existsByDriverIdAndStatusIn(
                                    d.getId(), List.of("ASSIGNED", "DRIVER_ON_THE_WAY", "ARRIVED", "STARTED", "IN_PROGRESS")
                            );
                            if (!hasActiveRide) {
                                // Check if already rejected in this dispatch round
                                List<BookingAssignment> priorOffers = bookingAssignmentRepository.findAllByBookingIdAndCandidateTypeAndCandidateId(booking.getId(), "DRIVER", d.getId());
                                boolean alreadyRejected = priorOffers.stream().anyMatch(po -> "REJECTED".equalsIgnoreCase(po.getStatus()));

                                if (!alreadyRejected) {
                                    double dist = HaversineDistanceUtil.calculateDistanceKm(pickupLat, pickupLon, d.getLatitude(), d.getLongitude());
                                    log.info("Checking driver ID {} (userId: {}), dist: {} km, minR: {}, maxR: {}",
                                            d.getId(), d.getUserId(), dist, minRadiusKm, maxRadiusKm);
                                    if (dist >= minRadiusKm && dist <= maxRadiusKm) {
                                        if (strategy.isDriverAvailable(d.getId(), booking)) {
                                            candidates.add(new CandidateWrapper("DRIVER", d.getId(), d.getUserId(), dist, d.getRating() != null ? d.getRating() : 4.0));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Search Contractors
        List<Contractor> activeContractors = contractorRepository.findAllByStatus("ACTIVE");
        for (Contractor c : activeContractors) {
            if (c.getLatitude() != null && c.getLongitude() != null) {
                if (c.getLocationUpdatedAt() == null || c.getLocationUpdatedAt().isAfter(staleThreshold)) {
                    List<BookingAssignment> prior = bookingAssignmentRepository.findAllByBookingIdAndCandidateTypeAndCandidateId(booking.getId(), "CONTRACTOR", c.getId());
                    boolean alreadyRejected = prior.stream().anyMatch(p -> "REJECTED".equalsIgnoreCase(p.getStatus()));

                    if (!alreadyRejected) {
                        double dist = HaversineDistanceUtil.calculateDistanceKm(pickupLat, pickupLon, c.getLatitude(), c.getLongitude());
                        if (dist >= minRadiusKm && dist <= maxRadiusKm) {
                            if (strategy.isContractorAvailable(c.getId(), booking)) {
                                candidates.add(new CandidateWrapper("CONTRACTOR", c.getId(), c.getUserId(), dist, c.getRating() != null ? c.getRating() : 4.0));
                            }
                        }
                    }
                }
            }
        }

        // Sort by Distance ASC, Rating DESC
        candidates.sort((c1, c2) -> {
            int distComp = Double.compare(c1.distanceKm, c2.distanceKm);
            if (distComp != 0) return distComp;
            return Double.compare(c2.rating, c1.rating);
        });

        return candidates;
    }

    private String getRequestedVehicleType(Long productId) {
        if (productId == null) return null;
        return productRepository.findById(productId)
                .map(p -> {
                    if (p.getCategory() != null && !p.getCategory().trim().isEmpty() && !"RIDE".equalsIgnoreCase(p.getCategory())) {
                        return p.getCategory();
                    }
                    if (p.getName() != null && !p.getName().trim().isEmpty()) {
                        return p.getName();
                    }
                    return p.getProductType();
                })
                .orElse(null);
    }

    public static boolean isVehicleTypeMatch(String driverVehicleType, String requestedVehicleType) {
        if (requestedVehicleType == null || requestedVehicleType.trim().isEmpty() || "RIDE".equalsIgnoreCase(requestedVehicleType) || "ALL".equalsIgnoreCase(requestedVehicleType)) {
            return true;
        }
        if (driverVehicleType == null || driverVehicleType.trim().isEmpty() || "ALL".equalsIgnoreCase(driverVehicleType)) {
            return true;
        }

        String dType = driverVehicleType.trim().toUpperCase();
        String rType = requestedVehicleType.trim().toUpperCase();

        if (dType.equals(rType)) return true;

        if (isMachineryGroup(dType) && isMachineryGroup(rType)) return true;
        if (dType.contains("CAR") && (rType.contains("CAR") || rType.contains("SEDAN") || rType.contains("SUV") || rType.contains("MINI") || rType.contains("HATCHBACK"))) return true;
        if (rType.contains("CAR") && (dType.contains("CAR") || dType.contains("SEDAN") || dType.contains("SUV") || dType.contains("MINI") || dType.contains("HATCHBACK"))) return true;
        if (dType.contains("AUTO") && rType.contains("AUTO")) return true;
        if (dType.contains("BIKE") && (rType.contains("BIKE") || rType.contains("SCOOTER"))) return true;
        if (dType.contains("SCOOTER") && (rType.contains("SCOOTER") || rType.contains("BIKE"))) return true;
        if (rType.contains("SCOOTER") && (dType.contains("SCOOTER") || dType.contains("BIKE"))) return true;
        if (rType.contains("BIKE") && (dType.contains("SCOOTER") || dType.contains("BIKE"))) return true;
        if ((dType.contains("MINI") || dType.contains("HATCHBACK")) && (rType.contains("MINI") || rType.contains("HATCHBACK") || rType.contains("CAR"))) return true;
        if (dType.contains("SEDAN") && (rType.contains("SEDAN") || rType.contains("CAR"))) return true;
        if (dType.contains("SUV") && (rType.contains("SUV") || rType.contains("CAR"))) return true;

        return false;
    }

    @Transactional
    public void dispatchScheduledBookingWithDriverAlerts(Long bookingId, int leadMinutes) {
        log.info("Dispatching scheduled booking ID {} with leadMinutes {}", bookingId, leadMinutes);
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("Scheduled booking ID {} not found", bookingId);
            return;
        }

        if (!"SCHEDULED".equalsIgnoreCase(booking.getStatus())) {
            log.info("Booking ID {} is not in SCHEDULED status (Current: {}). Skipping scheduled dispatch.", bookingId, booking.getStatus());
            return;
        }

        booking.setStatus("SEARCHING_DRIVER");
        bookingRepository.save(booking);

        recordEventHistory(bookingId, "SCHEDULED", "SEARCHING_DRIVER", booking.getCustomerId(), "SYSTEM",
                "Quartz scheduled dispatch triggered " + leadMinutes + " mins prior to pickup");

        // Broadcast to customer so UI transitions live
        webSocketNotificationService.sendBookingLifecycleEvent(bookingId, "SEARCHING_DRIVER", Map.of(
                "bookingId", bookingId,
                "status", "SEARCHING_DRIVER",
                "message", "Searching nearby drivers for your scheduled ride..."
        ));

        // Intimate nearby drivers via WebSocket and FCM Push Notification
        double pickupLat = booking.getPickupLatitude() != null ? booking.getPickupLatitude() : 0.0;
        double pickupLng = booking.getPickupLongitude() != null ? booking.getPickupLongitude() : 0.0;
        if ((pickupLat == 0.0 || pickupLng == 0.0) && booking.getPickupAddressId() != null) {
            Address pickupAddr = addressRepository.findById(booking.getPickupAddressId()).orElse(null);
            if (pickupAddr != null && pickupAddr.getLatitude() != null && pickupAddr.getLongitude() != null) {
                pickupLat = pickupAddr.getLatitude();
                pickupLng = pickupAddr.getLongitude();
            }
        }

        String targetVehicle = null;
        if (booking.getProductId() != null) {
            Product p = productRepository.findById(booking.getProductId()).orElse(null);
            if (p != null) {
                targetVehicle = (p.getCategory() != null && !p.getCategory().trim().isEmpty() && !"RIDE".equalsIgnoreCase(p.getCategory()))
                        ? p.getCategory() : (p.getName() != null ? p.getName() : p.getProductType());
            }
        }

        String pickupLocation = booking.getPickupAddressText() != null ? booking.getPickupAddressText() : "Nearby";
        String dropLocation = booking.getDestinationAddressText() != null ? booking.getDestinationAddressText() : "";
        Double totalFare = booking.getTotalAmount() != null ? booking.getTotalAmount() : booking.getEstimatedFare();

        List<Driver> activeDrivers = driverRepository.findAllByStatus("ACTIVE");
        int intimatedCount = 0;

        for (Driver d : activeDrivers) {
            if (!Boolean.TRUE.equals(d.getIsOnline())) continue;
            if (d.getLatitude() == null || d.getLongitude() == null) continue;

            if (isVehicleTypeMatch(d.getSelectedVehicleType(), targetVehicle)) {
                double dist = HaversineDistanceUtil.calculateDistanceKm(pickupLat, pickupLng, d.getLatitude(), d.getLongitude());
                if (dist <= 15.0) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("bookingId", booking.getId());
                    alert.put("bookingNumber", booking.getBookingNumber());
                    alert.put("pickupLocation", pickupLocation);
                    alert.put("dropLocation", dropLocation);
                    alert.put("scheduledPickupAt", booking.getScheduledPickupAt() != null ? booking.getScheduledPickupAt().toString() : "");
                    alert.put("leadMinutes", leadMinutes);
                    alert.put("distanceKm", Math.round(dist * 10.0) / 10.0);
                    alert.put("totalAmount", totalFare != null ? totalFare : 0.0);
                    alert.put("vehicleType", targetVehicle != null ? targetVehicle : "");
                    alert.put("message", "⏰ Scheduled Ride Alert: Pickup in " + leadMinutes + " mins!");

                    // 1. WebSocket alert to driver
                    webSocketNotificationService.sendScheduledRideAlertToDriver(d.getUserId(), alert);

                    // 2. FCM Push Notification
                    Map<String, String> fcmData = new HashMap<>();
                    fcmData.put("bookingId", String.valueOf(booking.getId()));
                    fcmData.put("bookingNumber", booking.getBookingNumber() != null ? booking.getBookingNumber() : "");
                    fcmData.put("type", "SCHEDULED_RIDE_ALERT");
                    fcmNotificationService.sendNotificationToUser(
                            d.getUserId(),
                            "⏰ Scheduled Ride in " + leadMinutes + " Mins!",
                            "Pickup at " + pickupLocation + " • ₹" + (totalFare != null ? totalFare : "0"),
                            fcmData
                    );

                    intimatedCount++;
                }
            }
        }
        log.info("Intimated {} active nearby drivers for scheduled booking ID {}", intimatedCount, bookingId);

        // Start auto assignment dispatch
        startAutoAssignmentProcess(booking.getId());
    }

    private static boolean isMachineryGroup(String type) {
        return type.contains("MACHINERY") || type.contains("JCB") || type.contains("EQUIPMENT") || type.contains("EXCAVATOR");
    }

    private void recordEventHistory(Long bookingId, String fromStatus, String toStatus, Long actorUserId, String actorRole, String reason) {
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
            log.error("Failed to record booking event history for booking {}: {}", bookingId, e.getMessage());
        }
    }

    private static class CandidateWrapper {
        String type;
        Long id;
        Long userId;
        double distanceKm;
        double rating;

        CandidateWrapper(String type, Long id, Long userId, double distanceKm, double rating) {
            this.type = type;
            this.id = id;
            this.userId = userId;
            this.distanceKm = distanceKm;
            this.rating = rating;
        }
    }
}
