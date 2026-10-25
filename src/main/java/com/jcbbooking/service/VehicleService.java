package com.jcbbooking.service;

import com.jcbbooking.model.Driver;
import com.jcbbooking.model.UserNotification;
import com.jcbbooking.model.Vehicle;
import com.jcbbooking.repository.UserNotificationRepository;
import com.jcbbooking.repository.VehicleRepository;
import com.jcbbooking.websocket.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final WebSocketNotificationService webSocketNotificationService;
    private final com.jcbbooking.repository.DriverRepository driverRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public List<Vehicle> getAllVehicles() {
        return vehicleRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Vehicle> getVehiclesByUserId(Long userId) {
        List<Vehicle> list = vehicleRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (list != null && list.size() > 1) {
            boolean hasConfigured = list.stream().anyMatch(v -> v.getRegNumber() != null && !v.getRegNumber().trim().isEmpty());
            if (hasConfigured) {
                List<Vehicle> placeholders = list.stream()
                        .filter(v -> (v.getRegNumber() == null || v.getRegNumber().trim().isEmpty()) && (v.getChassisVin() == null || v.getChassisVin().trim().isEmpty()))
                        .toList();
                for (Vehicle p : placeholders) {
                    try {
                        vehicleRepository.delete(p);
                        log.info("Cleaned up orphaned blank placeholder vehicle ID: {}", p.getId());
                    } catch (Exception e) {
                        log.warn("Could not delete placeholder vehicle: {}", e.getMessage());
                    }
                }
                list = vehicleRepository.findByUserIdOrderByCreatedAtDesc(userId);
            }
        }
        if (list == null || list.isEmpty()) {
            Driver d = driverRepository.findByUserId(userId).orElse(null);
            if (d != null) {
                Vehicle v = createDefaultVehicleIfNoneExists(userId, d.getId(), d.getSelectedVehicleType(), d.getSelectedMachineryModel());
                if (v != null) return List.of(v);
            }
        }
        if (list != null) {
            for (Vehicle v : list) {
                if (v.getVehicleTypeId() == null || v.getVehicleModelId() == null) {
                    autoResolveVehicleMasterIds(v);
                    try { vehicleRepository.save(v); } catch (Exception ignored) {}
                }
            }
        }
        return list;
    }

    public List<Vehicle> getVehiclesByDriverId(Long driverId) {
        List<Vehicle> list = vehicleRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
        if (list != null && list.size() > 1) {
            boolean hasConfigured = list.stream().anyMatch(v -> v.getRegNumber() != null && !v.getRegNumber().trim().isEmpty());
            if (hasConfigured) {
                List<Vehicle> placeholders = list.stream()
                        .filter(v -> (v.getRegNumber() == null || v.getRegNumber().trim().isEmpty()) && (v.getChassisVin() == null || v.getChassisVin().trim().isEmpty()))
                        .toList();
                for (Vehicle p : placeholders) {
                    try {
                        vehicleRepository.delete(p);
                        log.info("Cleaned up orphaned blank placeholder vehicle ID: {}", p.getId());
                    } catch (Exception e) {
                        log.warn("Could not delete placeholder vehicle: {}", e.getMessage());
                    }
                }
                list = vehicleRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
            }
        }
        if (list == null || list.isEmpty()) {
            Driver d = driverRepository.findById(driverId).orElse(null);
            if (d != null) {
                Vehicle v = createDefaultVehicleIfNoneExists(d.getUserId(), driverId, d.getSelectedVehicleType(), d.getSelectedMachineryModel());
                if (v != null) return List.of(v);
            }
        }
        if (list != null) {
            for (Vehicle v : list) {
                if (v.getVehicleTypeId() == null || v.getVehicleModelId() == null) {
                    autoResolveVehicleMasterIds(v);
                    try { vehicleRepository.save(v); } catch (Exception ignored) {}
                }
            }
        }
        return list;
    }

    public Optional<Vehicle> getVehicleById(Long id) {
        return vehicleRepository.findById(id);
    }

    @Transactional
    public Vehicle createVehicle(Vehicle vehicle) {
        if (vehicle.getSubmittedOn() == null || vehicle.getSubmittedOn().isEmpty()) {
            vehicle.setSubmittedOn(LocalDateTime.now().toString());
        }
        if (vehicle.getStatus() == null || vehicle.getStatus().isEmpty()) {
            vehicle.setStatus("UNDER_REVIEW");
        }

        // Deduplication check: return existing if regNumber and userId match an existing record
        if (vehicle.getRegNumber() != null && !vehicle.getRegNumber().trim().isEmpty() && vehicle.getUserId() != null) {
            List<Vehicle> existing = vehicleRepository.findByUserIdOrderByCreatedAtDesc(vehicle.getUserId());
            for (Vehicle v : existing) {
                if (v.getRegNumber() != null && vehicle.getRegNumber().trim().equalsIgnoreCase(v.getRegNumber().trim())) {
                    log.warn("Duplicate vehicle creation attempt detected for RegNumber: {} and UserId: {}. Returning existing record ID: {}", vehicle.getRegNumber(), vehicle.getUserId(), v.getId());
                    return v;
                }
            }
        }

        // If driver already has a blank/placeholder vehicle (created during registration with empty regNumber),
        // update that existing vehicle instead of creating an orphaned duplicate!
        if (vehicle.getDriverId() != null || vehicle.getUserId() != null) {
            List<Vehicle> existingList = vehicleRepository.findByUserIdOrDriverId(vehicle.getUserId(), vehicle.getDriverId());
            Vehicle placeholder = existingList.stream()
                    .filter(v -> v.getRegNumber() == null || v.getRegNumber().trim().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (placeholder != null) {
                log.info("Found placeholder vehicle ID: {} for Driver: {} / User: {}. Updating details instead of creating duplicate.",
                        placeholder.getId(), vehicle.getDriverId(), vehicle.getUserId());
                return updateVehicle(placeholder.getId(), vehicle);
            }
        }

        autoResolveVehicleMasterIds(vehicle);
        log.info("Creating new vehicle: {} (Reg: {}) for UserId: {}", vehicle.getVehicleName(), vehicle.getRegNumber(), vehicle.getUserId());
        return vehicleRepository.save(vehicle);
    }

    @Transactional
    public Vehicle updateVehicle(Long id, Vehicle updatedDetails) {
        Vehicle existing = vehicleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found with ID: " + id));

        if (updatedDetails.getVehicleTypeId() != null) existing.setVehicleTypeId(updatedDetails.getVehicleTypeId());
        if (updatedDetails.getVehicleModelId() != null) existing.setVehicleModelId(updatedDetails.getVehicleModelId());
        if (updatedDetails.getVehicleSubModelId() != null) existing.setVehicleSubModelId(updatedDetails.getVehicleSubModelId());
        if (updatedDetails.getRegNumber() != null) existing.setRegNumber(updatedDetails.getRegNumber());
        if (updatedDetails.getVehicleName() != null) existing.setVehicleName(updatedDetails.getVehicleName());
        if (updatedDetails.getCategory() != null) existing.setCategory(updatedDetails.getCategory());
        if (updatedDetails.getMachineryModel() != null) existing.setMachineryModel(updatedDetails.getMachineryModel());
        if (updatedDetails.getMachinerySubCategory() != null) existing.setMachinerySubCategory(updatedDetails.getMachinerySubCategory());
        if (updatedDetails.getExperience() != null) existing.setExperience(updatedDetails.getExperience());
        if (updatedDetails.getMfgYear() != null) existing.setMfgYear(updatedDetails.getMfgYear());
        if (updatedDetails.getMachineClass() != null) existing.setMachineClass(updatedDetails.getMachineClass());
        if (updatedDetails.getChassisVin() != null) existing.setChassisVin(updatedDetails.getChassisVin());
        if (updatedDetails.getEngineSerial() != null) existing.setEngineSerial(updatedDetails.getEngineSerial());
        if (updatedDetails.getPowertrain() != null) existing.setPowertrain(updatedDetails.getPowertrain());
        if (updatedDetails.getVehicleDescription() != null) existing.setVehicleDescription(updatedDetails.getVehicleDescription());
        if (updatedDetails.getRcFrontUrl() != null) existing.setRcFrontUrl(updatedDetails.getRcFrontUrl());
        if (updatedDetails.getRcBackUrl() != null) existing.setRcBackUrl(updatedDetails.getRcBackUrl());
        if (updatedDetails.getInsuranceUrl() != null) existing.setInsuranceUrl(updatedDetails.getInsuranceUrl());
        if (updatedDetails.getFrontPhotoUrl() != null) existing.setFrontPhotoUrl(updatedDetails.getFrontPhotoUrl());
        if (updatedDetails.getSidePhotoUrl() != null) existing.setSidePhotoUrl(updatedDetails.getSidePhotoUrl());
        if (updatedDetails.getRearPhotoUrl() != null) existing.setRearPhotoUrl(updatedDetails.getRearPhotoUrl());
        if (updatedDetails.getCabinPhotoUrl() != null) existing.setCabinPhotoUrl(updatedDetails.getCabinPhotoUrl());
        if (updatedDetails.getEnginePlatePhotoUrl() != null) existing.setEnginePlatePhotoUrl(updatedDetails.getEnginePlatePhotoUrl());
        if (updatedDetails.getStatus() != null) existing.setStatus(updatedDetails.getStatus());
        if (updatedDetails.getRejectionReason() != null) {
            existing.setRejectionReason(updatedDetails.getRejectionReason().trim().isEmpty() ? null : updatedDetails.getRejectionReason());
        } else if ("UNDER_REVIEW".equalsIgnoreCase(updatedDetails.getStatus()) || "PENDING_VERIFICATION".equalsIgnoreCase(updatedDetails.getStatus())) {
            existing.setRejectionReason(null);
        }

        autoResolveVehicleMasterIds(existing);
        return vehicleRepository.save(existing);
    }

    @Transactional
    public Vehicle approveVehicle(Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found with ID: " + id));

        vehicle.setStatus("APPROVED");
        vehicle.setIsActive(true);
        vehicle.setRejectionReason(null);
        log.info("Vehicle ID {} ({}) APPROVED", id, vehicle.getRegNumber());
        Vehicle saved = vehicleRepository.save(vehicle);

        sendVehicleStatusNotification(saved, true, null);
        return saved;
    }

    @Transactional
    public Vehicle rejectVehicle(Long id, String reason) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found with ID: " + id));

        vehicle.setStatus("REJECTED");
        vehicle.setIsActive(false);
        vehicle.setRejectionReason(reason);
        log.info("Vehicle ID {} ({}) REJECTED. Reason: {}", id, vehicle.getRegNumber(), reason);
        Vehicle saved = vehicleRepository.save(vehicle);

        sendVehicleStatusNotification(saved, false, reason);
        return saved;
    }

    private void sendVehicleStatusNotification(Vehicle vehicle, boolean isApproved, String reason) {
        if (vehicle.getUserId() == null) return;

        String title = isApproved ? "Vehicle Approved 🎉" : "Vehicle Verification Rejected ⚠️";
        String vName = vehicle.getVehicleName() != null ? vehicle.getVehicleName() : "Equipment";
        String reg = vehicle.getRegNumber() != null ? vehicle.getRegNumber() : "";
        String message = isApproved
                ? String.format("Your vehicle %s (Reg: %s) has been approved by admin and is active for job allocations!", vName, reg)
                : String.format("Your vehicle %s (Reg: %s) was rejected. Reason: %s", vName, reg, reason != null ? reason : "Document mismatch");

        try {
            UserNotification notification = UserNotification.builder()
                    .userId(vehicle.getUserId())
                    .targetType("INDIVIDUAL_USER")
                    .notificationType("VEHICLE")
                    .title(title)
                    .message(message)
                    .deepLink("/profile")
                    .isRead(false)
                    .build();
            userNotificationRepository.save(notification);

            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("type", "INBOX_NOTIFICATION");
            wsPayload.put("title", title);
            wsPayload.put("message", message);
            wsPayload.put("notificationType", "VEHICLE");
            wsPayload.put("deepLink", "/profile");

            webSocketNotificationService.sendBookingOfferToUser(vehicle.getUserId(), wsPayload);
            log.info("Dispatched vehicle status notification to UserId {}: {}", vehicle.getUserId(), title);
        } catch (Exception e) {
            log.warn("Failed to dispatch vehicle notification for UserId {}: {}", vehicle.getUserId(), e.getMessage());
        }
    }

    @Transactional
    public Vehicle activateVehicle(Long id, Long userId) {
        Vehicle target = vehicleRepository.findById(id).orElse(null);
        if (target == null) {
            throw new RuntimeException("Vehicle not found with ID: " + id);
        }

        Long driverId = target.getDriverId();
        Long effectiveUserId = (userId != null) ? userId : target.getUserId();

        List<Vehicle> userVehicles = List.of();
        if (driverId != null) {
            userVehicles = vehicleRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
        }
        if ((userVehicles == null || userVehicles.isEmpty()) && effectiveUserId != null) {
            userVehicles = vehicleRepository.findByUserIdOrderByCreatedAtDesc(effectiveUserId);
        }

        if (userVehicles != null) {
            for (Vehicle v : userVehicles) {
                boolean isMatch = v.getId().equals(id);
                v.setIsActive(isMatch);
                if (isMatch) {
                    v.setStatus("Active");
                } else if ("Active".equalsIgnoreCase(v.getStatus())) {
                    v.setStatus("APPROVED");
                }
                vehicleRepository.save(v);
            }
        }

        target.setIsActive(true);
        target.setStatus("Active");
        target = vehicleRepository.save(target);

        // Update Driver's selectedVehicleType and selectedMachineryModel
        Driver driver = null;
        if (driverId != null) {
            driver = driverRepository.findById(driverId).orElse(null);
        }
        if (driver == null && effectiveUserId != null) {
            driver = driverRepository.findByUserId(effectiveUserId).orElse(null);
        }
        if (driver != null) {
            driver.setSelectedVehicleType(target.getCategory());
            driver.setSelectedMachineryModel(target.getMachineryModel() != null ? target.getMachineryModel() : target.getVehicleName());
            driverRepository.save(driver);
            log.info("Activated vehicle ID {} for Driver ID {} with selectedVehicleType: {}", id, driver.getId(), target.getCategory());
        }

        return target;
    }

    @Transactional
    public Vehicle createDefaultVehicleIfNoneExists(Long userId, Long driverId, String vehicleType, String machineryModel) {
        if (userId == null && driverId == null) return null;

        List<Vehicle> existing = vehicleRepository.findByUserIdOrDriverId(userId, driverId);
        if (existing != null && !existing.isEmpty()) {
            return existing.get(0);
        }

        String category = (vehicleType != null && !vehicleType.trim().isEmpty()) ? vehicleType.trim() : "Machinery";
        String defaultName = "Machinery".equalsIgnoreCase(category) ? "JCB 3DX" : category;
        String model = (machineryModel != null && !machineryModel.trim().isEmpty()) ? machineryModel.trim() : defaultName;

        Vehicle defaultVehicle = Vehicle.builder()
                .userId(userId)
                .driverId(driverId)
                .vehicleName(model)
                .category(category)
                .machineryModel(model)
                .machinerySubCategory("")
                .regNumber("")
                .mfgYear("")
                .machineClass("")
                .chassisVin("")
                .engineSerial("")
                .vehicleDescription("")
                .status("UNDER_REVIEW")
                .isActive(true)
                .submittedOn(LocalDateTime.now().toString())
                .fetchMode("MANUAL")
                .isVahanSynced(false)
                .build();

        autoResolveVehicleMasterIds(defaultVehicle);
        Vehicle saved = vehicleRepository.save(defaultVehicle);
        log.info("Created blank vehicle record ID {} ({}) for UserId: {} / DriverId: {}", saved.getId(), model, userId, driverId);
        return saved;
    }

    public void autoResolveVehicleMasterIds(Vehicle v) {
        if (v == null || jdbcTemplate == null) return;
        try {
            // 1. Resolve vehicle_type_id if null
            if (v.getVehicleTypeId() == null && v.getCategory() != null && !v.getCategory().trim().isEmpty()) {
                String cat = v.getCategory().trim().toLowerCase();
                List<Map<String, Object>> types = jdbcTemplate.queryForList(
                    "SELECT id FROM vehicle_types WHERE LOWER(name) = ? OR LOWER(code) = ? LIMIT 1", cat, cat);
                if (!types.isEmpty()) {
                    Object val = types.get(0).get("id");
                    if (val instanceof Number) v.setVehicleTypeId(((Number) val).longValue());
                }
            }

            // 2. Resolve vehicle_model_id if null
            if (v.getVehicleModelId() == null) {
                String modelName = (v.getMachineryModel() != null && !v.getMachineryModel().trim().isEmpty())
                        ? v.getMachineryModel().trim()
                        : (v.getVehicleName() != null ? v.getVehicleName().trim() : null);
                if (modelName != null) {
                    String mName = modelName.toLowerCase();
                    List<Map<String, Object>> models = jdbcTemplate.queryForList(
                        "SELECT id, vehicle_type_id FROM vehicle_models WHERE LOWER(name) = ? LIMIT 1", mName);
                    if (!models.isEmpty()) {
                        Object mId = models.get(0).get("id");
                        if (mId instanceof Number) v.setVehicleModelId(((Number) mId).longValue());
                        if (v.getVehicleTypeId() == null) {
                            Object tId = models.get(0).get("vehicle_type_id");
                            if (tId instanceof Number) v.setVehicleTypeId(((Number) tId).longValue());
                        }
                    }
                }
            }

            // 3. Resolve vehicle_sub_model_id if null
            if (v.getVehicleSubModelId() == null) {
                String subName = (v.getMachinerySubCategory() != null && !v.getMachinerySubCategory().trim().isEmpty())
                        ? v.getMachinerySubCategory().trim()
                        : (v.getVehicleName() != null ? v.getVehicleName().trim() : null);
                if (subName != null) {
                    String sName = subName.toLowerCase();
                    List<Map<String, Object>> subModels = jdbcTemplate.queryForList(
                        "SELECT id, vehicle_model_id FROM vehicle_sub_models WHERE LOWER(name) = ? LIMIT 1", sName);
                    if (!subModels.isEmpty()) {
                        Object smId = subModels.get(0).get("id");
                        if (smId instanceof Number) v.setVehicleSubModelId(((Number) smId).longValue());
                        if (v.getVehicleModelId() == null) {
                            Object mId = subModels.get(0).get("vehicle_model_id");
                            if (mId instanceof Number) v.setVehicleModelId(((Number) mId).longValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not auto-resolve vehicle master IDs: {}", e.getMessage());
        }
    }
}
