package com.jcbbooking.controller;

import java.io.File;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jcbbooking.model.Driver;
import com.jcbbooking.model.Role;
import com.jcbbooking.model.User;
import com.jcbbooking.repository.ContractorRepository;
import com.jcbbooking.repository.DocumentRepository;
import com.jcbbooking.repository.DriverRepository;
import com.jcbbooking.repository.UserRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.util.ApiResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
@Slf4j
public class DriverController {

    private final DriverRepository driverRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ContractorRepository contractorRepository;
    private final DocumentRepository documentRepository;
    private final com.jcbbooking.repository.PartnerApprovalRepository partnerApprovalRepository;
    private final com.jcbbooking.repository.VehicleRepository vehicleRepository;
    private final com.jcbbooking.repository.ProductRepository productRepository;
    private final com.jcbbooking.repository.BookingSettingRepository bookingSettingRepository;

    @Value("${core.fileTransfer.primaryUploadFolder:/opt/microservice/upload/images}")
    private String primaryUploadFolder;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Driver>>> getAllDrivers(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get all drivers");
        if (userDetails != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CONTRACTOR) {
                Optional<com.jcbbooking.model.Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty()) {
                    conOpt = contractorRepository.findByPhone(userDetails.getUser().getPhone());
                }
                if (conOpt.isPresent()) {
                    List<Driver> contractorDrivers = driverRepository.findAllByContractorId(conOpt.get().getId());
                    return ResponseEntity.ok(ApiResponse.success("Drivers retrieved successfully", contractorDrivers));
                }
                return ResponseEntity.ok(ApiResponse.success("No assigned drivers found", List.of()));
            } else if (role == Role.DRIVER) {
                Optional<Driver> selfOpt = driverRepository.findByUserId(userDetails.getId());
                if (selfOpt.isEmpty()) {
                    selfOpt = driverRepository.findByPhone(userDetails.getUser().getPhone());
                }
                return selfOpt.map(driver -> ResponseEntity.ok(ApiResponse.success("Driver retrieved successfully", List.of(driver))))
                        .orElseGet(() -> ResponseEntity.ok(ApiResponse.success("Driver not found", List.of())));
            }
        }
        return ResponseEntity.ok(ApiResponse.success("Drivers retrieved successfully", driverRepository.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Driver>> getDriverById(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get driver by id: {}", id);
        Driver driver = driverRepository.findById(id).orElse(null);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        if (userDetails != null) {
            Role role = userDetails.getUser().getRole();
            if (role == Role.CONTRACTOR) {
                Optional<com.jcbbooking.model.Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isEmpty()) {
                    conOpt = contractorRepository.findByPhone(userDetails.getUser().getPhone());
                }
                if (conOpt.isEmpty() || !conOpt.get().getId().equals(driver.getContractorId())) {
                    return ResponseEntity.status(403).body(ApiResponse.error("Unauthorized access to driver details"));
                }
            } else if (role == Role.DRIVER) {
                Optional<Driver> selfOpt = driverRepository.findByUserId(userDetails.getId());
                if (selfOpt.isEmpty()) {
                    selfOpt = driverRepository.findByPhone(userDetails.getUser().getPhone());
                }
                if (selfOpt.isEmpty() || !selfOpt.get().getId().equals(driver.getId())) {
                    return ResponseEntity.status(403).body(ApiResponse.error("Unauthorized access to driver details"));
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success("Driver retrieved successfully", driver));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> saveDriver(
            @RequestBody Driver driver,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to save/update driver: {}", driver);
        boolean isNew = (driver.getId() == null || driver.getId() == 0);

        if (isNew) {
            driver.setId(null);
            if (driverRepository.existsByPhone(driver.getPhone())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Driver with phone already exists"));
            }
            if (driver.getStatus() == null) {
                driver.setStatus("PENDING_VERIFICATION");
            }
            if (driver.getRating() == null) {
                driver.setRating(0.0);
            }
            if (driver.getTotalJobs() == null) {
                driver.setTotalJobs(0);
            }
            if (driver.getTotalEarnings() == null) {
                driver.setTotalEarnings(0.0);
            }
            
            // Auto-assign contractorId if created by a Contractor user
            if (userDetails != null && userDetails.getUser().getRole() == Role.CONTRACTOR) {
                java.util.Optional<com.jcbbooking.model.Contractor> conOpt = contractorRepository.findByUserId(userDetails.getId());
                if (conOpt.isPresent()) {
                    driver.setContractorId(conOpt.get().getId());
                }
            }
        } else {
            Driver existing = driverRepository.findById(driver.getId()).orElse(null);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }

            // Sync User creation if status is set to ACTIVE
            if ("ACTIVE".equals(driver.getStatus()) && !"ACTIVE".equals(existing.getStatus())) {
                syncUserAccount(existing.getPhone(), existing.getFullName(), existing.getEmail(), Role.DRIVER, existing.getContractorId(), existing.getId(), true);
            }

            // Sync User deactivation if status is set to SUSPENDED
            if ("SUSPENDED".equals(driver.getStatus()) && !"SUSPENDED".equals(existing.getStatus())) {
                syncUserAccount(existing.getPhone(), existing.getFullName(), existing.getEmail(), Role.DRIVER, existing.getContractorId(), existing.getId(), false);
            }

            // Update fields
            existing.setFullName(driver.getFullName());
            existing.setPhone(driver.getPhone());
            existing.setEmail(driver.getEmail());
            existing.setLicenseNumber(driver.getLicenseNumber());
            existing.setAadhaarNumber(driver.getAadhaarNumber());
            existing.setExperience(driver.getExperience());
            if (driver.getContractorId() != null) {
                existing.setContractorId(driver.getContractorId());
            }
            if (driver.getStatus() != null) {
                existing.setStatus(driver.getStatus());
                syncPartnerApprovalStatus(existing.getPhone(), driver.getStatus());
            }
            driver = existing;
        }

        Driver saved = driverRepository.save(driver);
        return ResponseEntity.ok(ApiResponse.success(isNew ? "Driver application submitted" : "Driver updated successfully", saved));
    }

    private User syncUserAccount(String phone, String fullName, String email, Role role, Long contractorId, Long driverId, boolean active) {
        if (phone == null || phone.trim().isEmpty()) {
            return null;
        }
        String customPass = partnerApprovalRepository.findByPhone(phone)
                .map(com.jcbbooking.model.PartnerApproval::getPassword)
                .filter(p -> p != null && !p.trim().isEmpty())
                .orElse("Password123");

        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null) {
            user = User.builder()
                    .fullName(fullName != null ? fullName : "Driver Partner")
                    .phone(phone)
                    .email(email)
                    .role(role)
                    .contractorId(contractorId)
                    .driverId(driverId)
                    .active(active)
                    .verified(true)
                    .passwordHash(passwordEncoder.encode(customPass))
                    .build();
            user = userRepository.save(user);
            log.info("Created missing User entry ID {} for driver phone {}, active={}", user.getId(), phone, active);
        } else {
            user.setActive(active);
            user.setVerified(true);
            if (!"Password123".equals(customPass)) {
                user.setPasswordHash(passwordEncoder.encode(customPass));
            }
            if (role != null) user.setRole(role);
            if (contractorId != null) user.setContractorId(contractorId);
            if (driverId != null) user.setDriverId(driverId);
            if (fullName != null && !fullName.trim().isEmpty()) user.setFullName(fullName);
            if (email != null && !email.trim().isEmpty()) user.setEmail(email);
            user = userRepository.save(user);
            log.info("Updated existing User entry ID {} for driver phone {}, active={}", user.getId(), phone, active);
        }
        return user;
    }

    private void syncPartnerApprovalStatus(String phone, String status) {
        if (phone != null && !phone.trim().isEmpty()) {
            partnerApprovalRepository.findByPhone(phone).ifPresent(pa -> {
                pa.setStatus(status);
                partnerApprovalRepository.save(pa);
                log.info("Synced PartnerApproval status to {} for driver phone {}", status, phone);
            });
        }
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> approveDriver(@PathVariable Long id) {
        log.info("REST request to approve driver ID: {}", id);
        Driver driver = driverRepository.findById(id).orElse(null);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        User user = syncUserAccount(driver.getPhone(), driver.getFullName(), driver.getEmail(), Role.DRIVER, driver.getContractorId(), driver.getId(), true);
        if (user != null) {
            driver.setUserId(user.getId());
        }
        driver.setStatus("ACTIVE");
        syncPartnerApprovalStatus(driver.getPhone(), "ACTIVE");
        Driver saved = driverRepository.save(driver);

        return ResponseEntity.ok(ApiResponse.success("Driver approved successfully and user account activated", saved));
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> rejectDriver(@PathVariable Long id) {
        log.info("REST request to reject driver ID: {}", id);
        Driver driver = driverRepository.findById(id).orElse(null);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        driver.setStatus("REJECTED");
        syncPartnerApprovalStatus(driver.getPhone(), "REJECTED");
        Driver saved = driverRepository.save(driver);
        return ResponseEntity.ok(ApiResponse.success("Driver application rejected", saved));
    }

    @PostMapping("/{id}/suspend")
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> suspendDriver(@PathVariable Long id) {
        log.info("REST request to suspend driver ID: {}", id);
        Driver driver = driverRepository.findById(id).orElse(null);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        driver.setStatus("SUSPENDED");
        syncPartnerApprovalStatus(driver.getPhone(), "SUSPENDED");
        User user = syncUserAccount(driver.getPhone(), driver.getFullName(), driver.getEmail(), Role.DRIVER, driver.getContractorId(), driver.getId(), false);
        if (user != null) {
            driver.setUserId(user.getId());
        }
        Driver saved = driverRepository.save(driver);
        return ResponseEntity.ok(ApiResponse.success("Driver suspended successfully and user account deactivated", saved));
    }

    @PostMapping("/{id}/unsuspend")
    @Transactional
    public ResponseEntity<ApiResponse<Driver>> unsuspendDriver(@PathVariable Long id) {
        log.info("REST request to unsuspend driver ID: {}", id);
        Driver driver = driverRepository.findById(id).orElse(null);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        driver.setStatus("ACTIVE");
        syncPartnerApprovalStatus(driver.getPhone(), "ACTIVE");
        User user = syncUserAccount(driver.getPhone(), driver.getFullName(), driver.getEmail(), Role.DRIVER, driver.getContractorId(), driver.getId(), true);
        if (user != null) {
            driver.setUserId(user.getId());
        }
        Driver saved = driverRepository.save(driver);
        return ResponseEntity.ok(ApiResponse.success("Driver activated successfully and user account activated", saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteDriver(@PathVariable Long id) {
        log.info("REST request to delete driver ID: {}", id);
        if (!driverRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        // Cascade-delete all associated documents (files + DB records)
        documentRepository.findAllByEntityTypeAndEntityId("DRIVER", id).forEach(doc -> {
            try {
                File file = new File(doc.getFilePath());
                if (file.exists()) {
                    file.delete();
                    log.info("Deleted document file: {}", doc.getFilePath());
                }
            } catch (Exception ex) {
                log.warn("Could not delete document file: {}", doc.getFilePath(), ex);
            }
            documentRepository.delete(doc);
        });
        driverRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("Driver deleted successfully"));
    }

    @GetMapping("/search-nearby")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> searchNearbyDrivers(
            @org.springframework.web.bind.annotation.RequestParam double latitude,
            @org.springframework.web.bind.annotation.RequestParam double longitude,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Double radiusKm,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String vehicleType,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long productId) {

        double effectiveRadius = (radiusKm != null && radiusKm > 0) ? radiusKm : 10.0;
        if (radiusKm == null || radiusKm <= 0) {
            com.jcbbooking.model.BookingSetting setting = bookingSettingRepository.findBySettingKey("DEFAULT_SETTINGS")
                    .orElseGet(() -> bookingSettingRepository.findAll().stream().findFirst().orElse(null));
            if (setting != null && setting.getInitialRadiusKm() != null) {
                effectiveRadius = setting.getInitialRadiusKm();
            }
        }

        log.info("REST request to search nearby drivers lat: {}, lon: {}, radius: {} km, vehicleType: {}, productId: {}",
                latitude, longitude, effectiveRadius, vehicleType, productId);

        String effectiveVehicleType = vehicleType;
        if ((effectiveVehicleType == null || effectiveVehicleType.trim().isEmpty()) && productId != null) {
            effectiveVehicleType = productRepository.findById(productId)
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

        // Query real drivers from database only (NO mock data overwrite)
        List<Driver> activeDrivers = driverRepository.findAllByStatus("ACTIVE");
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();

        for (Driver d : activeDrivers) {
            if (!Boolean.TRUE.equals(d.getIsOnline())) {
                continue;
            }
            if (d.getLatitude() != null && d.getLongitude() != null) {
                if (com.jcbbooking.service.BookingAssignmentService.isVehicleTypeMatch(d.getSelectedVehicleType(), effectiveVehicleType)) {
                    double dist = com.jcbbooking.util.HaversineDistanceUtil.calculateDistanceKm(latitude, longitude, d.getLatitude(), d.getLongitude());
                    if (dist <= effectiveRadius) {
                        java.util.Map<String, Object> item = new java.util.HashMap<>();
                        item.put("driverId", d.getId());
                        item.put("userId", d.getUserId());
                        item.put("fullName", d.getFullName());
                        item.put("phone", d.getPhone());
                        item.put("rating", d.getRating() != null ? d.getRating() : 4.8);
                        item.put("latitude", d.getLatitude());
                        item.put("longitude", d.getLongitude());
                        item.put("distanceKm", Math.round(dist * 100.0) / 100.0);
                        item.put("selectedVehicleType", d.getSelectedVehicleType());
                        item.put("selectedMachineryModel", d.getSelectedMachineryModel());
                        item.put("isOnline", d.getIsOnline() != null ? d.getIsOnline() : true);

                        if (d.getId() != null) {
                            List<com.jcbbooking.model.Vehicle> vList = vehicleRepository.findByDriverIdOrderByCreatedAtDesc(d.getId());
                            com.jcbbooking.model.Vehicle activeVehicle = vList.stream().filter(v -> Boolean.TRUE.equals(v.getIsActive())).findFirst().orElse(null);
                            if (activeVehicle != null) {
                                item.put("activeVehicleId", activeVehicle.getId());
                                item.put("regNumber", activeVehicle.getRegNumber());
                                item.put("vehicleName", activeVehicle.getVehicleName());
                                item.put("category", activeVehicle.getCategory());
                            }
                        }
                        result.add(item);
                    }
                }
            }
        }

        result.sort((m1, m2) -> Double.compare((Double) m1.get("distanceKm"), (Double) m2.get("distanceKm")));
        return ResponseEntity.ok(ApiResponse.success("Nearby active drivers retrieved successfully", result));
    }
}
