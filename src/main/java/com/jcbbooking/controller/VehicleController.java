package com.jcbbooking.controller;

import com.jcbbooking.model.Driver;
import com.jcbbooking.model.Vehicle;
import com.jcbbooking.repository.DriverRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jcbbooking.websocket.WebSocketNotificationService;

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*")
public class VehicleController {

    private final VehicleService vehicleService;
    private final DriverRepository driverRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyVehicles(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request for authenticated driver vehicles: {}", userDetails != null ? userDetails.getId() : "null");
        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
        }
        Long userId = userDetails.getId();
        String rawPhone = userDetails.getUser().getPhone();
        String cleanPhone = rawPhone != null ? rawPhone.replaceAll("[^0-9]", "") : "";
        if (cleanPhone.length() > 10) {
            cleanPhone = cleanPhone.substring(cleanPhone.length() - 10);
        }

        Driver driver = driverRepository.findByUserId(userId)
                .orElseGet(() -> (rawPhone != null ? driverRepository.findByPhone(rawPhone).orElse(null) : null));
        if (driver == null && !cleanPhone.isEmpty()) {
            final String last10 = cleanPhone;
            driver = driverRepository.findAll().stream()
                    .filter(d -> d.getPhone() != null && d.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                    .findFirst().orElse(null);
        }
        Long driverId = driver != null ? driver.getId() : null;

        List<Vehicle> list = List.of();
        if (driverId != null) {
            list = vehicleService.getVehiclesByDriverId(driverId);
        }
        if ((list == null || list.isEmpty()) && userId != null) {
            list = vehicleService.getVehiclesByUserId(userId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", list);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllVehicles() {
        List<Vehicle> list = vehicleService.getAllVehicles();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", list);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getVehiclesByUserId(@PathVariable Long userId) {
        List<Vehicle> list = vehicleService.getVehiclesByUserId(userId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", list);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/driver/{driverId}")
    public ResponseEntity<Map<String, Object>> getVehiclesByDriverId(@PathVariable Long driverId) {
        List<Vehicle> list = vehicleService.getVehiclesByDriverId(driverId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", list);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getVehicleById(@PathVariable Long id) {
        Vehicle vehicle = vehicleService.getVehicleById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found: " + id));
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", vehicle);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createVehicle(@RequestBody Vehicle vehicle) {
        Vehicle saved = vehicleService.createVehicle(vehicle);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Vehicle created successfully");
        response.put("data", saved);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateVehicle(@PathVariable Long id, @RequestBody Vehicle details) {
        Vehicle updated = vehicleService.updateVehicle(id, details);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Vehicle updated successfully");
        response.put("data", updated);
        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/{id}/approve", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> approveVehicle(@PathVariable Long id) {
        Vehicle approved = vehicleService.approveVehicle(id);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Vehicle approved successfully");
        response.put("data", approved);
        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/{id}/reject", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> rejectVehicle(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String reasonParam) {
        String reason = "Documents incomplete or invalid";
        if (body != null && body.containsKey("reason") && body.get("reason") != null && !body.get("reason").trim().isEmpty()) {
            reason = body.get("reason").trim();
        } else if (reasonParam != null && !reasonParam.trim().isEmpty()) {
            reason = reasonParam.trim();
        }
        Vehicle rejected = vehicleService.rejectVehicle(id, reason);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Vehicle rejected successfully");
        response.put("data", rejected);
        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/{id}/activate", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> activateVehicle(
            @PathVariable Long id,
            @RequestParam(required = false) Long userId) {
        Vehicle activated = vehicleService.activateVehicle(id, userId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Vehicle activated successfully");
        response.put("data", activated);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/invite-operator")
    public ResponseEntity<Map<String, Object>> inviteOperator(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String phone = body != null && body.containsKey("phone") ? String.valueOf(body.get("phone")).trim() : "";
        if (phone.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Phone number is required"));
        }

        Vehicle vehicle = vehicleService.getVehicleById(id).orElse(null);
        String vehicleName = vehicle != null ? vehicle.getVehicleName() : "JCB Equipment";
        String regNum = vehicle != null ? vehicle.getRegNumber() : "";

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("type", "OPERATOR_INVITATION");
        wsPayload.put("vehicleId", id);
        wsPayload.put("vehicleName", vehicleName);
        wsPayload.put("regNumber", regNum);
        wsPayload.put("phone", phone);
        wsPayload.put("message", "You have been invited to operate vehicle: " + vehicleName + " (" + regNum + ")");
        wsPayload.put("timestamp", System.currentTimeMillis());

        webSocketNotificationService.sendOperatorInvitationToDriver(phone, wsPayload);

        log.info("Sent WebSocket operator invitation for vehicle {} to phone {}", id, phone);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "WebSocket & Push Notification invitation sent successfully to " + phone);
        response.put("data", wsPayload);
        return ResponseEntity.ok(response);
    }
}
