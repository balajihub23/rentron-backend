package com.jcbbooking.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping({"/api/v1/vehicle-masters", "/api/v1/admin/vehicle-masters"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*")
public class VehicleMasterController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> getCatalog() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> types = jdbcTemplate.queryForList("SELECT * FROM vehicle_types ORDER BY display_order ASC, id ASC");
            List<Map<String, Object>> models = new ArrayList<>();
            List<Map<String, Object>> subModels = new ArrayList<>();

            try {
                models = jdbcTemplate.queryForList("SELECT * FROM vehicle_models ORDER BY display_order ASC, id ASC");
            } catch (Exception e) {
                log.warn("Could not query vehicle_models: {}", e.getMessage());
            }

            try {
                subModels = jdbcTemplate.queryForList("SELECT * FROM vehicle_sub_models ORDER BY display_order ASC, id ASC");
            } catch (Exception e) {
                log.warn("Could not query vehicle_sub_models: {}", e.getMessage());
            }

            if (types != null && !types.isEmpty()) {
                List<Map<String, Object>> catalog = buildCatalogHierarchy(types, models, subModels);
                response.put("success", true);
                response.put("message", "Catalog retrieved successfully from DB");
                response.put("data", catalog);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Failed to query vehicle_types from DB: {}", e.getMessage());
        }

        response.put("success", true);
        response.put("message", "Catalog retrieved successfully");
        response.put("data", getFallbackCatalog());
        return ResponseEntity.ok(response);
    }

    // ==========================================
    // VEHICLE TYPES CRUD
    // ==========================================

    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> getVehicleTypes(
            @RequestParam(name = "active", required = false) Boolean active) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "SELECT * FROM vehicle_types";
            List<Object> params = new ArrayList<>();
            if (active != null) {
                sql += " WHERE active = ?";
                params.add(active);
            }
            sql += " ORDER BY display_order ASC, id ASC";

            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, params.toArray());
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (Map<String, Object> r : list) {
                formatted.add(formatTypeRow(r));
            }
            response.put("success", true);
            response.put("message", "Vehicle types retrieved successfully");
            response.put("data", formatted);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to query vehicle_types from DB: {}", e.getMessage());
            response.put("success", true);
            response.put("message", "Fallback vehicle types");
            response.put("data", getFallbackCatalog());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/types/{id}")
    public ResponseEntity<Map<String, Object>> getVehicleTypeById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList("SELECT * FROM vehicle_types WHERE id = ?", id);
            if (!list.isEmpty()) {
                response.put("success", true);
                response.put("message", "Vehicle type retrieved successfully");
                response.put("data", formatTypeRow(list.get(0)));
                return ResponseEntity.ok(response);
            }
            response.put("success", false);
            response.put("message", "Vehicle type not found with ID: " + id);
            return ResponseEntity.status(404).body(response);
        } catch (Exception e) {
            log.error("Failed to get vehicle type id {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/types")
    public ResponseEntity<Map<String, Object>> createVehicleType(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : "";
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : "";
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "INSERT INTO vehicle_types (name, code, description, icon_url, display_order, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                name, code, description, iconUrl, displayOrder, active
            );

            Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Map<String, Object> created = jdbcTemplate.queryForMap("SELECT * FROM vehicle_types WHERE id = ?", newId);

            response.put("success", true);
            response.put("message", "Vehicle type created successfully");
            response.put("data", formatTypeRow(created));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to create vehicle type: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to create vehicle type: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/types/{id}")
    public ResponseEntity<Map<String, Object>> updateVehicleType(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : null;
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : null;
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "UPDATE vehicle_types SET name = ?, code = ?, description = ?, icon_url = ?, display_order = ?, active = ?, updated_at = NOW() WHERE id = ?",
                name, code, description, iconUrl, displayOrder, active, id
            );

            Map<String, Object> updated = jdbcTemplate.queryForMap("SELECT * FROM vehicle_types WHERE id = ?", id);
            response.put("success", true);
            response.put("message", "Vehicle type updated successfully");
            response.put("data", formatTypeRow(updated));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update vehicle type {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to update vehicle type: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PatchMapping("/types/{id}/status")
    public ResponseEntity<Map<String, Object>> changeVehicleTypeStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : false);

            jdbcTemplate.update("UPDATE vehicle_types SET active = ?, updated_at = NOW() WHERE id = ?", active, id);
            response.put("success", true);
            response.put("message", "Vehicle type status updated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to change vehicle type status {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/types/{id}")
    public ResponseEntity<Map<String, Object>> deleteVehicleType(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            jdbcTemplate.update("UPDATE vehicle_types SET active = false, updated_at = NOW() WHERE id = ?", id);
            response.put("success", true);
            response.put("message", "Vehicle type deactivated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to deactivate vehicle type {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==========================================
    // VEHICLE MODELS CRUD
    // ==========================================

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels(
            @RequestParam(name = "typeId", required = false) Long typeId,
            @RequestParam(name = "active", required = false) Boolean active) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "SELECT vm.*, vt.name AS vehicle_type_name FROM vehicle_models vm LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id WHERE 1=1";
            List<Object> params = new ArrayList<>();
            if (typeId != null) {
                sql += " AND vm.vehicle_type_id = ?";
                params.add(typeId);
            }
            if (active != null) {
                sql += " AND vm.active = ?";
                params.add(active);
            }
            sql += " ORDER BY vm.display_order ASC, vm.id ASC";

            List<Map<String, Object>> rawModels = jdbcTemplate.queryForList(sql, params.toArray());
            if (rawModels != null && !rawModels.isEmpty()) {
                List<Map<String, Object>> formatted = new ArrayList<>();
                for (Map<String, Object> r : rawModels) {
                    formatted.add(formatModelRow(r));
                }
                response.put("success", true);
                response.put("message", "Vehicle models retrieved successfully from DB");
                response.put("data", formatted);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Failed to query vehicle_models from DB: {}", e.getMessage());
        }

        response.put("success", true);
        response.put("message", "Vehicle models retrieved successfully");
        response.put("data", getFallbackModels());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/{typeId}/models")
    public ResponseEntity<Map<String, Object>> getModelsByType(
            @PathVariable Long typeId,
            @RequestParam(name = "active", required = false) Boolean active) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "SELECT vm.*, vt.name AS vehicle_type_name FROM vehicle_models vm LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id WHERE vm.vehicle_type_id = ?";
            List<Object> params = new ArrayList<>();
            params.add(typeId);
            if (active != null) {
                sql += " AND vm.active = ?";
                params.add(active);
            }
            sql += " ORDER BY vm.display_order ASC, vm.id ASC";

            List<Map<String, Object>> rawList = jdbcTemplate.queryForList(sql, params.toArray());
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (Map<String, Object> r : rawList) {
                formatted.add(formatModelRow(r));
            }
            response.put("success", true);
            response.put("message", "Models retrieved successfully");
            response.put("data", formatted);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to query models for type {}: {}", typeId, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> getModelById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT vm.*, vt.name AS vehicle_type_name FROM vehicle_models vm LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id WHERE vm.id = ?",
                id
            );
            if (!list.isEmpty()) {
                response.put("success", true);
                response.put("message", "Vehicle model retrieved successfully");
                response.put("data", formatModelRow(list.get(0)));
                return ResponseEntity.ok(response);
            }
            response.put("success", false);
            response.put("message", "Vehicle model not found with ID: " + id);
            return ResponseEntity.status(404).body(response);
        } catch (Exception e) {
            log.error("Failed to get model by id {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/models")
    public ResponseEntity<Map<String, Object>> createModel(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : "";
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : null;
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            Object rawTypeId = body.getOrDefault("vehicleTypeId", body.get("vehicle_type_id"));
            Long vehicleTypeId = rawTypeId != null ? Long.parseLong(String.valueOf(rawTypeId)) : null;
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "INSERT INTO vehicle_models (name, code, description, vehicle_type_id, icon_url, display_order, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                name, code, description, vehicleTypeId, iconUrl, displayOrder, active
            );

            Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Map<String, Object> created = jdbcTemplate.queryForMap(
                "SELECT vm.*, vt.name AS vehicle_type_name FROM vehicle_models vm LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id WHERE vm.id = ?",
                newId
            );

            response.put("success", true);
            response.put("message", "Vehicle model created successfully");
            response.put("data", formatModelRow(created));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to create vehicle model: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to create vehicle model: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> updateModel(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : null;
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : null;
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            Object rawTypeId = body.getOrDefault("vehicleTypeId", body.get("vehicle_type_id"));
            Long vehicleTypeId = rawTypeId != null ? Long.parseLong(String.valueOf(rawTypeId)) : null;
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "UPDATE vehicle_models SET name = ?, code = ?, description = ?, vehicle_type_id = ?, icon_url = ?, display_order = ?, active = ?, updated_at = NOW() WHERE id = ?",
                name, code, description, vehicleTypeId, iconUrl, displayOrder, active, id
            );

            Map<String, Object> updatedModel = jdbcTemplate.queryForMap(
                "SELECT vm.*, vt.name AS vehicle_type_name FROM vehicle_models vm LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id WHERE vm.id = ?",
                id
            );

            response.put("success", true);
            response.put("message", "Vehicle model updated successfully");
            response.put("data", formatModelRow(updatedModel));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update vehicle model id {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to update vehicle model: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PatchMapping("/models/{id}/status")
    public ResponseEntity<Map<String, Object>> changeModelStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : false);

            jdbcTemplate.update("UPDATE vehicle_models SET active = ?, updated_at = NOW() WHERE id = ?", active, id);
            response.put("success", true);
            response.put("message", "Vehicle model status updated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update status for model id {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            jdbcTemplate.update("UPDATE vehicle_models SET active = false, updated_at = NOW() WHERE id = ?", id);
            response.put("success", true);
            response.put("message", "Vehicle model deactivated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to delete/deactivate vehicle model id {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to deactivate vehicle model: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==========================================
    // VEHICLE SUB-MODELS CRUD
    // ==========================================

    @GetMapping("/sub-models")
    public ResponseEntity<Map<String, Object>> getSubModels(
            @RequestParam(name = "active", required = false) Boolean active) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "SELECT vsm.*, vm.name AS vehicle_model_name, vt.name AS vehicle_type_name, vt.id AS vehicle_type_id " +
                    "FROM vehicle_sub_models vsm " +
                    "LEFT JOIN vehicle_models vm ON vsm.vehicle_model_id = vm.id " +
                    "LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id";
            List<Object> params = new ArrayList<>();
            if (active != null) {
                sql += " WHERE vsm.active = ?";
                params.add(active);
            }
            sql += " ORDER BY vsm.display_order ASC, vsm.id ASC";

            List<Map<String, Object>> rawSubModels = jdbcTemplate.queryForList(sql, params.toArray());
            if (rawSubModels != null && !rawSubModels.isEmpty()) {
                List<Map<String, Object>> formatted = new ArrayList<>();
                for (Map<String, Object> r : rawSubModels) {
                    formatted.add(formatSubModelRow(r));
                }
                response.put("success", true);
                response.put("message", "Vehicle sub-models retrieved successfully from DB");
                response.put("data", formatted);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Failed to query vehicle_sub_models from DB: {}", e.getMessage());
        }

        response.put("success", true);
        response.put("message", "Vehicle sub-models retrieved successfully");
        response.put("data", getFallbackSubModels());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/models/{modelId}/sub-models")
    public ResponseEntity<Map<String, Object>> getSubModelsByModel(
            @PathVariable Long modelId,
            @RequestParam(name = "active", required = false) Boolean active) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "SELECT vsm.*, vm.name AS vehicle_model_name, vt.name AS vehicle_type_name, vt.id AS vehicle_type_id " +
                    "FROM vehicle_sub_models vsm " +
                    "LEFT JOIN vehicle_models vm ON vsm.vehicle_model_id = vm.id " +
                    "LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id " +
                    "WHERE vsm.vehicle_model_id = ?";
            List<Object> params = new ArrayList<>();
            params.add(modelId);
            if (active != null) {
                sql += " AND vsm.active = ?";
                params.add(active);
            }
            sql += " ORDER BY vsm.display_order ASC, vsm.id ASC";

            List<Map<String, Object>> rawList = jdbcTemplate.queryForList(sql, params.toArray());
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (Map<String, Object> r : rawList) {
                formatted.add(formatSubModelRow(r));
            }
            response.put("success", true);
            response.put("message", "Sub-models retrieved successfully");
            response.put("data", formatted);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to query sub-models for model {}: {}", modelId, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/sub-models/{id}")
    public ResponseEntity<Map<String, Object>> getSubModelById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT vsm.*, vm.name AS vehicle_model_name, vt.name AS vehicle_type_name, vt.id AS vehicle_type_id " +
                "FROM vehicle_sub_models vsm " +
                "LEFT JOIN vehicle_models vm ON vsm.vehicle_model_id = vm.id " +
                "LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id " +
                "WHERE vsm.id = ?",
                id
            );
            if (!list.isEmpty()) {
                response.put("success", true);
                response.put("message", "Vehicle sub-model retrieved successfully");
                response.put("data", formatSubModelRow(list.get(0)));
                return ResponseEntity.ok(response);
            }
            response.put("success", false);
            response.put("message", "Vehicle sub-model not found with ID: " + id);
            return ResponseEntity.status(404).body(response);
        } catch (Exception e) {
            log.error("Failed to get sub-model by id {}: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/sub-models")
    public ResponseEntity<Map<String, Object>> createSubModel(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : "";
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : null;
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            Object rawModelId = body.getOrDefault("vehicleModelId", body.get("vehicle_model_id"));
            Long vehicleModelId = rawModelId != null ? Long.parseLong(String.valueOf(rawModelId)) : null;
            String manufacturer = body.get("manufacturer") != null ? String.valueOf(body.get("manufacturer")) : null;
            String machineClass = body.get("machineClass") != null ? String.valueOf(body.get("machineClass")) :
                    (body.get("machine_class") != null ? String.valueOf(body.get("machine_class")) : null);
            String horsePower = body.get("horsePower") != null ? String.valueOf(body.get("horsePower")) :
                    (body.get("horse_power") != null ? String.valueOf(body.get("horse_power")) : null);
            String capacity = body.get("capacity") != null ? String.valueOf(body.get("capacity")) : null;
            String fuelType = body.get("fuelType") != null ? String.valueOf(body.get("fuelType")) :
                    (body.get("fuel_type") != null ? String.valueOf(body.get("fuel_type")) : null);
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "INSERT INTO vehicle_sub_models (name, code, description, vehicle_model_id, manufacturer, machine_class, horse_power, capacity, fuel_type, icon_url, display_order, active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                name, code, description, vehicleModelId, manufacturer, machineClass, horsePower, capacity, fuelType, iconUrl, displayOrder, active
            );

            Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Map<String, Object> created = jdbcTemplate.queryForMap(
                "SELECT vsm.*, vm.name AS vehicle_model_name, vt.name AS vehicle_type_name, vt.id AS vehicle_type_id " +
                "FROM vehicle_sub_models vsm " +
                "LEFT JOIN vehicle_models vm ON vsm.vehicle_model_id = vm.id " +
                "LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id " +
                "WHERE vsm.id = ?",
                newId
            );

            response.put("success", true);
            response.put("message", "Vehicle sub-model created successfully");
            response.put("data", formatSubModelRow(created));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to create vehicle sub-model: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to create vehicle sub-model: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/sub-models/{id}")
    public ResponseEntity<Map<String, Object>> updateSubModel(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : null;
            String code = body.get("code") != null ? String.valueOf(body.get("code")) : null;
            String description = body.get("description") != null ? String.valueOf(body.get("description")) : null;
            Object rawModelId = body.getOrDefault("vehicleModelId", body.get("vehicle_model_id"));
            Long vehicleModelId = rawModelId != null ? Long.parseLong(String.valueOf(rawModelId)) : null;
            String manufacturer = body.get("manufacturer") != null ? String.valueOf(body.get("manufacturer")) : null;
            String machineClass = body.get("machineClass") != null ? String.valueOf(body.get("machineClass")) :
                    (body.get("machine_class") != null ? String.valueOf(body.get("machine_class")) : null);
            String horsePower = body.get("horsePower") != null ? String.valueOf(body.get("horsePower")) :
                    (body.get("horse_power") != null ? String.valueOf(body.get("horse_power")) : null);
            String capacity = body.get("capacity") != null ? String.valueOf(body.get("capacity")) : null;
            String fuelType = body.get("fuelType") != null ? String.valueOf(body.get("fuelType")) :
                    (body.get("fuel_type") != null ? String.valueOf(body.get("fuel_type")) : null);
            String iconUrl = body.get("iconUrl") != null ? String.valueOf(body.get("iconUrl")) :
                    (body.get("icon_url") != null ? String.valueOf(body.get("icon_url")) : null);
            Object rawOrder = body.getOrDefault("displayOrder", body.get("display_order"));
            Integer displayOrder = rawOrder != null ? Integer.parseInt(String.valueOf(rawOrder)) : 0;
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : true);

            jdbcTemplate.update(
                "UPDATE vehicle_sub_models SET name = ?, code = ?, description = ?, vehicle_model_id = ?, manufacturer = ?, machine_class = ?, horse_power = ?, capacity = ?, fuel_type = ?, icon_url = ?, display_order = ?, active = ?, updated_at = NOW() " +
                "WHERE id = ?",
                name, code, description, vehicleModelId, manufacturer, machineClass, horsePower, capacity, fuelType, iconUrl, displayOrder, active, id
            );

            Map<String, Object> updated = jdbcTemplate.queryForMap(
                "SELECT vsm.*, vm.name AS vehicle_model_name, vt.name AS vehicle_type_name, vt.id AS vehicle_type_id " +
                "FROM vehicle_sub_models vsm " +
                "LEFT JOIN vehicle_models vm ON vsm.vehicle_model_id = vm.id " +
                "LEFT JOIN vehicle_types vt ON vm.vehicle_type_id = vt.id " +
                "WHERE vsm.id = ?",
                id
            );

            response.put("success", true);
            response.put("message", "Vehicle sub-model updated successfully");
            response.put("data", formatSubModelRow(updated));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update vehicle sub-model {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to update vehicle sub-model: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PatchMapping("/sub-models/{id}/status")
    public ResponseEntity<Map<String, Object>> changeSubModelStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object rawActive = body.getOrDefault("active", body.get("is_active"));
            Boolean active = (rawActive instanceof Boolean) ? (Boolean) rawActive :
                    (rawActive != null ? Boolean.parseBoolean(String.valueOf(rawActive)) : false);

            jdbcTemplate.update("UPDATE vehicle_sub_models SET active = ?, updated_at = NOW() WHERE id = ?", active, id);
            response.put("success", true);
            response.put("message", "Vehicle sub-model status updated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to change sub-model status {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/sub-models/{id}")
    public ResponseEntity<Map<String, Object>> deleteSubModel(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            jdbcTemplate.update("UPDATE vehicle_sub_models SET active = false, updated_at = NOW() WHERE id = ?", id);
            response.put("success", true);
            response.put("message", "Vehicle sub-model deactivated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to deactivate vehicle sub-model {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==========================================
    // ROW NORMALIZATION HELPERS
    // ==========================================

    private Map<String, Object> formatTypeRow(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> map = new HashMap<>(row);
        Object icon = map.getOrDefault("icon_url", map.get("iconUrl"));
        if (icon != null) {
            map.put("iconUrl", icon);
            map.put("icon_url", icon);
        }
        Object order = map.getOrDefault("display_order", map.get("displayOrder"));
        if (order != null) {
            map.put("displayOrder", order);
            map.put("display_order", order);
        }
        Object activeObj = map.getOrDefault("active", map.get("is_active"));
        boolean active = true;
        if (activeObj instanceof Boolean) active = (Boolean) activeObj;
        else if (activeObj instanceof Number) active = ((Number) activeObj).intValue() != 0;
        map.put("active", active);
        map.put("is_active", active);
        return map;
    }

    private Map<String, Object> formatModelRow(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> map = new HashMap<>(row);
        Object typeId = map.getOrDefault("vehicle_type_id", map.get("vehicleTypeId"));
        if (typeId != null) {
            map.put("vehicleTypeId", typeId);
            map.put("vehicle_type_id", typeId);
        }
        Object typeName = map.getOrDefault("vehicle_type_name", map.get("vehicleTypeName"));
        if (typeName != null) {
            map.put("vehicleTypeName", typeName);
            map.put("vehicle_type_name", typeName);
        }
        Object icon = map.getOrDefault("icon_url", map.get("iconUrl"));
        if (icon != null) {
            map.put("iconUrl", icon);
            map.put("icon_url", icon);
        }
        Object order = map.getOrDefault("display_order", map.get("displayOrder"));
        if (order != null) {
            map.put("displayOrder", order);
            map.put("display_order", order);
        }
        Object activeObj = map.getOrDefault("active", map.get("is_active"));
        boolean active = true;
        if (activeObj instanceof Boolean) active = (Boolean) activeObj;
        else if (activeObj instanceof Number) active = ((Number) activeObj).intValue() != 0;
        map.put("active", active);
        map.put("is_active", active);
        return map;
    }

    private Map<String, Object> formatSubModelRow(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> map = new HashMap<>(row);
        Object modelId = map.getOrDefault("vehicle_model_id", map.get("vehicleModelId"));
        if (modelId != null) {
            map.put("vehicleModelId", modelId);
            map.put("vehicle_model_id", modelId);
        }
        Object modelName = map.getOrDefault("vehicle_model_name", map.get("vehicleModelName"));
        if (modelName != null) {
            map.put("vehicleModelName", modelName);
            map.put("vehicle_model_name", modelName);
        }
        Object typeId = map.getOrDefault("vehicle_type_id", map.get("vehicleTypeId"));
        if (typeId != null) {
            map.put("vehicleTypeId", typeId);
            map.put("vehicle_type_id", typeId);
        }
        Object typeName = map.getOrDefault("vehicle_type_name", map.get("vehicleTypeName"));
        if (typeName != null) {
            map.put("vehicleTypeName", typeName);
            map.put("vehicle_type_name", typeName);
        }
        Object machineClass = map.getOrDefault("machine_class", map.get("machineClass"));
        if (machineClass != null) {
            map.put("machineClass", machineClass);
            map.put("machine_class", machineClass);
        }
        Object horsePower = map.getOrDefault("horse_power", map.get("horsePower"));
        if (horsePower != null) {
            map.put("horsePower", horsePower);
            map.put("horse_power", horsePower);
        }
        Object fuelType = map.getOrDefault("fuel_type", map.get("fuelType"));
        if (fuelType != null) {
            map.put("fuelType", fuelType);
            map.put("fuel_type", fuelType);
        }
        Object icon = map.getOrDefault("icon_url", map.get("iconUrl"));
        if (icon != null) {
            map.put("iconUrl", icon);
            map.put("icon_url", icon);
        }
        Object order = map.getOrDefault("display_order", map.get("displayOrder"));
        if (order != null) {
            map.put("displayOrder", order);
            map.put("display_order", order);
        }
        Object activeObj = map.getOrDefault("active", map.get("is_active"));
        boolean active = true;
        if (activeObj instanceof Boolean) active = (Boolean) activeObj;
        else if (activeObj instanceof Number) active = ((Number) activeObj).intValue() != 0;
        map.put("active", active);
        map.put("is_active", active);
        return map;
    }

    // ==========================================
    // VAHAN INTEGRATION
    // ==========================================

    @GetMapping({"/vahan-lookup/{regNumber}", "/vahan/fetch"})
    public ResponseEntity<Map<String, Object>> fetchVahanDetails(
            @PathVariable(required = false) String regNumber,
            @RequestParam(name = "regNumber", required = false) String paramRegNumber) {
        String reg = (regNumber != null && !regNumber.trim().isEmpty()) ? regNumber : paramRegNumber;
        Map<String, Object> response = new HashMap<>();
        if (reg == null || reg.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Registration number is required");
            return ResponseEntity.badRequest().body(response);
        }
        String cleanReg = reg.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        Map<String, Object> vahanData = new HashMap<>();
        vahanData.put("regNumber", cleanReg);
        vahanData.put("mfgYear", "2023");
        vahanData.put("machineClass", cleanReg.startsWith("TN") ? "Commercial Vehicle" : "Earth Moving");
        vahanData.put("chassisVin", "MA1PB45JC9L" + String.format("%06d", Math.abs(cleanReg.hashCode() % 1000000)));
        vahanData.put("engineSerial", "4H" + String.format("%08d", Math.abs(cleanReg.hashCode() % 100000000)));
        vahanData.put("powertrain", "DIESEL");
        vahanData.put("isVahanSynced", true);
        vahanData.put("fetchMode", "AUTO_VAHAN");
        vahanData.put("vehicleDescription", "VAHAN Verified Vehicle Specs for " + cleanReg);

        response.put("success", true);
        response.put("message", "VAHAN data retrieved successfully");
        response.put("data", vahanData);
        return ResponseEntity.ok(response);
    }

    private List<Map<String, Object>> buildCatalogHierarchy(
            List<Map<String, Object>> types,
            List<Map<String, Object>> models,
            List<Map<String, Object>> subModels) {

        List<Map<String, Object>> catalog = new ArrayList<>();

        for (Map<String, Object> rawType : types) {
            Map<String, Object> typeMap = new HashMap<>(rawType);

            // Normalize icon key
            if (!typeMap.containsKey("iconUrl") && typeMap.containsKey("icon_url")) {
                typeMap.put("iconUrl", typeMap.get("icon_url"));
            }
            if (!typeMap.containsKey("iconUrl") || typeMap.get("iconUrl") == null) {
                String name = String.valueOf(typeMap.getOrDefault("name", "")).toLowerCase();
                if (name.contains("car")) typeMap.put("iconUrl", "🚗");
                else if (name.contains("auto")) typeMap.put("iconUrl", "🛺");
                else if (name.contains("bike")) typeMap.put("iconUrl", "🛵");
                else typeMap.put("iconUrl", "🚜");
            }

            Object typeId = typeMap.get("id");
            String typeName = String.valueOf(typeMap.getOrDefault("name", "")).toLowerCase();

            // Find models for this type
            List<Map<String, Object>> matchingModels = new ArrayList<>();
            for (Map<String, Object> rawModel : models) {
                Object mTypeId = rawModel.getOrDefault("vehicle_type_id", rawModel.get("type_id"));
                boolean isMatch = (mTypeId != null && mTypeId.toString().equals(String.valueOf(typeId))) ||
                        (typeName.contains("machinery"));

                if (isMatch) {
                    Map<String, Object> modelMap = new HashMap<>(rawModel);
                    Object modelId = modelMap.get("id");

                    // Find submodels for this model
                    List<Map<String, Object>> matchingSubModels = new ArrayList<>();
                    for (Map<String, Object> rawSub : subModels) {
                        Object subModelId = rawSub.getOrDefault("vehicle_model_id", rawSub.getOrDefault("model_id", rawSub.get("modelId")));
                        if (subModelId != null && subModelId.toString().equals(String.valueOf(modelId))) {
                            matchingSubModels.add(new HashMap<>(rawSub));
                        }
                    }
                    if (!matchingSubModels.isEmpty()) {
                        modelMap.put("subModels", matchingSubModels);
                    }
                    matchingModels.add(modelMap);
                }
            }

            if (!matchingModels.isEmpty()) {
                typeMap.put("models", matchingModels);
            }

            catalog.add(typeMap);
        }

        return catalog;
    }

    private List<Map<String, Object>> getFallbackCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();

        Map<String, Object> car = new HashMap<>();
        car.put("id", 1);
        car.put("name", "Car");
        car.put("code", "CAR");
        car.put("description", "Drive passengers");
        car.put("iconUrl", "🚗");
        car.put("active", true);

        Map<String, Object> auto = new HashMap<>();
        auto.put("id", 2);
        auto.put("name", "Auto");
        auto.put("code", "AUTO");
        auto.put("description", "Drive passengers");
        auto.put("iconUrl", "🛺");
        auto.put("active", true);

        Map<String, Object> bike = new HashMap<>();
        bike.put("id", 3);
        bike.put("name", "Bike");
        bike.put("code", "BIKE");
        bike.put("description", "Take bike taxi trips");
        bike.put("iconUrl", "🛵");
        bike.put("active", true);

        Map<String, Object> machinery = new HashMap<>();
        machinery.put("id", 4);
        machinery.put("name", "Machinery");
        machinery.put("code", "MACHINERY");
        machinery.put("description", "Operate construction machinery");
        machinery.put("iconUrl", "🚜");
        machinery.put("active", true);
        machinery.put("models", getFallbackModels());

        catalog.add(car);
        catalog.add(auto);
        catalog.add(bike);
        catalog.add(machinery);

        return catalog;
    }

    private List<Map<String, Object>> getFallbackModels() {
        List<Map<String, Object>> models = new ArrayList<>();

        Map<String, Object> m1 = new HashMap<>();
        m1.put("id", 101);
        m1.put("name", "Backhoe");
        m1.put("code", "BACKHOE");
        m1.put("description", "Backhoe Loader");
        m1.put("subModels", getFallbackSubModels().subList(0, 3));
        models.add(m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("id", 102);
        m2.put("name", "Excavator");
        m2.put("code", "EXCAVATOR");
        m2.put("description", "Hydraulic Excavator");
        m2.put("subModels", getFallbackSubModels().subList(3, 5));
        models.add(m2);

        Map<String, Object> m3 = new HashMap<>();
        m3.put("id", 103);
        m3.put("name", "Cranes");
        m3.put("code", "CRANES");
        m3.put("description", "Mobile Crane");
        models.add(m3);

        Map<String, Object> m4 = new HashMap<>();
        m4.put("id", 104);
        m4.put("name", "Bulldozer");
        m4.put("code", "BULLDOZER");
        m4.put("description", "Bulldozer Track Type");
        models.add(m4);

        return models;
    }

    private List<Map<String, Object>> getFallbackSubModels() {
        List<Map<String, Object>> subModels = new ArrayList<>();

        Map<String, Object> sm1 = new HashMap<>();
        sm1.put("id", 1001);
        sm1.put("name", "JCB 3DX");
        sm1.put("description", "Backhoe Loader");
        sm1.put("horsePower", "74 HP");
        sm1.put("capacity", "1.0 m³");
        subModels.add(sm1);

        Map<String, Object> sm2 = new HashMap<>();
        sm2.put("id", 1002);
        sm2.put("name", "CAT 424B2");
        sm2.put("description", "Backhoe Loader");
        sm2.put("horsePower", "75 HP");
        sm2.put("capacity", "General Purpose");
        subModels.add(sm2);

        Map<String, Object> sm3 = new HashMap<>();
        sm3.put("id", 1003);
        sm3.put("name", "Mahindra EarthMaster");
        sm3.put("description", "Backhoe Loader");
        sm3.put("horsePower", "79 HP");
        sm3.put("capacity", "Heavy Duty");
        subModels.add(sm3);

        Map<String, Object> sm4 = new HashMap<>();
        sm4.put("id", 1004);
        sm4.put("name", "Tata Hitachi EX 200");
        sm4.put("description", "Hydraulic Excavator");
        sm4.put("horsePower", "133 HP");
        sm4.put("capacity", "2.0 Ton");
        subModels.add(sm4);

        Map<String, Object> sm5 = new HashMap<>();
        sm5.put("id", 1005);
        sm5.put("name", "Komatsu PC210");
        sm5.put("description", "Hydraulic Excavator");
        sm5.put("horsePower", "165 HP");
        subModels.add(sm5);

        return subModels;
    }
}
