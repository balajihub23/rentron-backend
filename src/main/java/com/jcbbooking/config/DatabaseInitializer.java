package com.jcbbooking.config;

import com.jcbbooking.model.*;
import com.jcbbooking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final PermissionRepository permissionRepository;
    private final RoleMenuAccessRepository roleMenuAccessRepository;
    private final RolePermissionAccessRepository rolePermissionAccessRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final DriverRepository driverRepository;
    private final ContractorRepository contractorRepository;
    private final ProductRepository productRepository;
    private final VehicleRepository vehicleRepository;
    private final RentalPackageRepository rentalPackageRepository;
    private final PricingRepository pricingRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("Checking database state for initialization...");

        // Automatically clean up deprecated verification columns from database if they exist
        try {
            Number emailExists = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'email_verified' AND table_schema = DATABASE()")
                    .getSingleResult();
            if (emailExists != null && emailExists.intValue() > 0) {
                entityManager.createNativeQuery("ALTER TABLE users DROP COLUMN email_verified").executeUpdate();
                log.info("Successfully dropped email_verified column from users table");
            }
        } catch (Exception e) {
            log.warn("Error checking/dropping email_verified column: {}", e.getMessage());
        }

        try {
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN front_photo_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN rc_front_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN rc_back_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN insurance_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN side_photo_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN rear_photo_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN cabin_photo_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN engine_plate_photo_url LONGTEXT").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE vehicles MODIFY COLUMN vehicle_description LONGTEXT").executeUpdate();
            log.info("Successfully altered vehicles table URL columns to LONGTEXT");
        } catch (Exception e) {
            log.warn("Auto-alter vehicles columns to LONGTEXT note: {}", e.getMessage());
        }



        try {
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN estimated_fare DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN final_fare DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN total_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN base_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN distance_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN time_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN waiting_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN driver_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN operator_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN booking_fee DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN discount_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN tax_amount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE bookings MODIFY COLUMN driver_tip DECIMAL(12,2) DEFAULT 0.00").executeUpdate();

            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN base_price DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN per_km_price DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN per_minute_price DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN per_hour_price DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN daily_rate DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN extra_hour_rate DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN discount DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN minimum_fare DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN waiting_charge DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN driver_charge DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN operator_charge DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN booking_fee DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN cancellation_fee DECIMAL(12,2) DEFAULT 0.00").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE pricings MODIFY COLUMN night_allowance DECIMAL(12,2) DEFAULT 300.00").executeUpdate();
            log.info("Successfully verified and adjusted monetary column precision to DECIMAL(12,2)");
        } catch (Exception e) {
            log.warn("Monetary column precision adjustment note: {}", e.getMessage());
        }

        if (userRepository.count() == 0) {
            log.info("Database is empty. Commencing system seeding...");
            initializeData();
        } else {
            log.info("Database already contains data. Dynamic menus active from database.");
            log.info("Checking password hashes for secure BCrypt formatting...");
            List<User> users = userRepository.findAll();
            int migratedCount = 0;
            for (User user : users) {
                String hash = user.getPasswordHash();
                if (hash != null && !hash.startsWith("$2a$") && !hash.startsWith("$2b$") && !hash.startsWith("$2y$")) {
                    log.info("Legacy plaintext password detected for user: [{}]. Auto-encrypting to secure BCrypt hash...", user.getEmail());
                    user.setPasswordHash(passwordEncoder.encode(hash));
                    userRepository.save(user);
                    migratedCount++;
                }
            }
            if (migratedCount > 0) {
                log.info("Successfully migrated {} legacy passwords to secure BCrypt hashes!", migratedCount);
            }
        }

        // Initialize Dynamic Rental Packages (Phase 1 Task 2)
        seedRentalPackages();
    }

    private void seedRentalPackages() {
        if (rentalPackageRepository.count() == 0) {
            log.info("Seeding dynamic rental packages...");
            rentalPackageRepository.save(RentalPackage.builder()
                    .name("1 hr 10 km")
                    .vehicleCategory("ALL")
                    .includedHours(1.0)
                    .includedKm(10.0)
                    .basePackageFare(349.0)
                    .extraKmPrice(11.0)
                    .extraHourPrice(90.0)
                    .bookingFee(18.0)
                    .taxPercentage(5.0)
                    .active(true)
                    .build());

            rentalPackageRepository.save(RentalPackage.builder()
                    .name("2 hr 20 km")
                    .vehicleCategory("ALL")
                    .includedHours(2.0)
                    .includedKm(20.0)
                    .basePackageFare(599.0)
                    .extraKmPrice(11.0)
                    .extraHourPrice(90.0)
                    .bookingFee(18.0)
                    .taxPercentage(5.0)
                    .active(true)
                    .build());

            rentalPackageRepository.save(RentalPackage.builder()
                    .name("4 hr 40 km")
                    .vehicleCategory("ALL")
                    .includedHours(4.0)
                    .includedKm(40.0)
                    .basePackageFare(1099.0)
                    .extraKmPrice(11.0)
                    .extraHourPrice(90.0)
                    .bookingFee(18.0)
                    .taxPercentage(5.0)
                    .active(true)
                    .build());

            rentalPackageRepository.save(RentalPackage.builder()
                    .name("8 hr 80 km")
                    .vehicleCategory("ALL")
                    .includedHours(8.0)
                    .includedKm(80.0)
                    .basePackageFare(1999.0)
                    .extraKmPrice(11.0)
                    .extraHourPrice(90.0)
                    .bookingFee(18.0)
                    .taxPercentage(5.0)
                    .active(true)
                    .build());
            log.info("Default rental packages seeded successfully!");
        }
    }

    private Menu updateOrCreateMenu(String name, String code, Menu parent, String path, String icon, int order) {
        Menu menu = menuRepository.findAll().stream()
                .filter(m -> code.equalsIgnoreCase(m.getMenuCode()))
                .findFirst()
                .orElse(null);
        if (menu == null) {
            menu = Menu.builder()
                    .menuCode(code)
                    .menuName(name)
                    .routePath(path)
                    .icon(icon)
                    .displayOrder(order)
                    .parentMenu(parent)
                    .active(true)
                    .build();
        } else {
            // Preserve user's active/inactive setting for existing menus
            menu.setMenuName(name);
            menu.setRoutePath(path);
            menu.setIcon(icon);
            menu.setDisplayOrder(order);
            if (menu.getActive() == null) {
                menu.setActive(true);
            }
        }
        menu = menuRepository.save(menu);

        if (!roleMenuAccessRepository.existsByRoleAndMenu(Role.ADMIN, menu)) {
            roleMenuAccessRepository.save(RoleMenuAccess.builder().role(Role.ADMIN).menu(menu).build());
        }
        return menu;
    }

    private void initializeData() {
        // 1. Seed Permissions
        log.info("Seeding permissions...");
        List<Permission> permissions = new ArrayList<>();
        
        // Customer permissions
        permissions.add(createPermission("Create Booking", "booking:create"));
        permissions.add(createPermission("Manage Addresses", "address:manage"));
        permissions.add(createPermission("Track Bookings", "booking:track"));
        permissions.add(createPermission("Process Payments", "payment:pay"));
        
        // Driver permissions
        permissions.add(createPermission("Receive Booking requests", "booking:receive"));
        permissions.add(createPermission("Accept Bookings", "booking:accept"));
        permissions.add(createPermission("Complete Booking", "booking:complete"));
        permissions.add(createPermission("Upload Work Proof", "proof:upload"));

        // Admin permissions
        permissions.add(createPermission("Manage Contractors", "contractor:manage"));
        permissions.add(createPermission("Approve Drivers", "driver:approve"));
        permissions.add(createPermission("Monitor Bookings", "booking:monitor"));
        permissions.add(createPermission("Monitor Payments", "payment:monitor"));
        permissions.add(createPermission("Manage Menus", "menu:manage"));
        permissions.add(createPermission("Manage Permissions", "permission:manage"));

        // Contractor permissions
        permissions.add(createPermission("Manage Assigned Drivers", "contractor:driver_manage"));
        permissions.add(createPermission("View Assigned Bookings", "contractor:assigned_bookings"));
        permissions.add(createPermission("View Earnings Summary", "contractor:view_earnings"));

        List<Permission> savedPermissions = permissionRepository.saveAll(permissions);

        // 2. Seed initial default menus if database is empty
        log.info("Seeding initial system menus...");
        Menu dashboard = updateOrCreateMenu("Dashboard", "dashboard", null, "/dashboard", "dashboard-icon", 1);
        Menu partner = updateOrCreateMenu("Partner", "partners", null, "/partners", "people-icon", 2);
        Menu product = updateOrCreateMenu("Product", "products", null, "/products", "box-icon", 3);
        Menu booking = updateOrCreateMenu("Booking", "bookings", null, "/bookings", "calendar-icon", 4);
        Menu payment = updateOrCreateMenu("Payment", "payments", null, "/payments", "money-icon", 5);
        Menu payout = updateOrCreateMenu("Payout Requests", "payouts", null, "/payouts", "credit-card-icon", 6);
        Menu support = updateOrCreateMenu("Support", "support", null, "/support", "chat-icon", 7);
        Menu settings = updateOrCreateMenu("Setting", "settings", null, "/settings", "settings-icon", 8);

        Menu bookingSetting = updateOrCreateMenu("Booking Settings", "booking_settings", settings, "/settings/booking", "sliders-icon", 1);
        Menu menuSetup = updateOrCreateMenu("Menu Management", "menu_setup", settings, "/settings/menus", "list-icon", 2);
        Menu permissionSetup = updateOrCreateMenu("Permission Setup", "permission_setup", settings, "/settings/permissions", "shield-icon", 3);

        // 4. Set up Role-Permission mappings
        log.info("Mapping permissions to roles...");
        
        // ADMIN gets all operational privileges
        for (Permission perm : savedPermissions) {
            String code = perm.getPermissionCode();
            if (code.startsWith("contractor:") || code.startsWith("driver:") || code.startsWith("booking:monitor") ||
                code.startsWith("payment:monitor") || code.startsWith("menu:") || code.startsWith("permission:")) {
                grantPermissionAccess(Role.ADMIN, perm);
            }
        }

        // CONTRACTOR gets contractor-specific functional scopes
        for (Permission perm : savedPermissions) {
            String code = perm.getPermissionCode();
            if (code.startsWith("contractor:driver_manage") || code.startsWith("contractor:assigned_bookings") ||
                code.startsWith("contractor:view_earnings")) {
                grantPermissionAccess(Role.CONTRACTOR, perm);
            }
        }

        // CUSTOMER gets customer-specific functional scopes
        for (Permission perm : savedPermissions) {
            String code = perm.getPermissionCode();
            if (code.equals("booking:create") || code.equals("address:manage") ||
                code.equals("booking:track") || code.equals("payment:pay")) {
                grantPermissionAccess(Role.CUSTOMER, perm);
            }
        }

        // DRIVER gets driver-specific functional scopes
        for (Permission perm : savedPermissions) {
            String code = perm.getPermissionCode();
            if (code.equals("booking:receive") || code.equals("booking:accept") ||
                code.equals("booking:complete") || code.equals("proof:upload")) {
                grantPermissionAccess(Role.DRIVER, perm);
            }
        }

        // 5. Seed Users for each of the four roles
        log.info("Creating default seed users...");

        // Admin Account
        createSeedUser("System Administrator", "+919876543210", "admin@jcbbooking.com", "AdminPassword123", Role.ADMIN, true, true);

        // Contractor Account (Rule 4: verified=true, active=true to login)
        createSeedUser("John Contractor", "+919876543211", "john@contractor.com", "ContractorPassword123", Role.CONTRACTOR, true, true);

        // Driver Account (Rule 3: verified=true, active=true to login)
        createSeedUser("David Driver", "+919876543212", "david@driver.com", "DriverPassword123", Role.DRIVER, true, true);

        // Customer Account
        createSeedUser("Robert Customer", "+919876543213", "robert@customer.com", "CustomerPassword123", Role.CUSTOMER, true, true);

        // Seed Drivers
        log.info("Seeding Driver applications...");
        User seededDriverUser = userRepository.findByPhone("+919876543212").orElse(null);
        driverRepository.save(Driver.builder()
                .fullName("David Driver")
                .phone("+919876543212")
                .email("david@driver.com")
                .userId(seededDriverUser != null ? seededDriverUser.getId() : null)
                .licenseNumber("DL-0120210012345")
                .aadhaarNumber("999988887777")
                .experience("8 years")
                .rating(4.8)
                .totalJobs(66)
                .totalEarnings(8500.0)
                .status("ACTIVE")
                .build());

        driverRepository.save(Driver.builder()
                .fullName("Rajesh Kumar")
                .phone("+919811122333")
                .email("rajesh.k@example.com")
                .licenseNumber("DL-0120230045678")
                .aadhaarNumber("444455554567")
                .experience("6 years")
                .rating(4.5)
                .totalJobs(145)
                .totalEarnings(12000.0)
                .status("PENDING_VERIFICATION")
                .build());

        driverRepository.save(Driver.builder()
                .fullName("Suresh Kumar")
                .phone("+919811122334")
                .email("suresh.k@example.com")
                .licenseNumber("DL-0120240099999")
                .aadhaarNumber("111122223333")
                .experience("1.5 years")
                .rating(3.8)
                .totalJobs(10)
                .totalEarnings(1500.0)
                .status("OFFLINE")
                .build());

        driverRepository.save(Driver.builder()
                .fullName("Ramesh Kumar")
                .phone("+919811122335")
                .email("ramesh.k@example.com")
                .licenseNumber("DL-0120240088888")
                .aadhaarNumber("555566667777")
                .experience("5 years")
                .rating(4.2)
                .totalJobs(75)
                .totalEarnings(7500.0)
                .status("BUSY")
                .build());

        // Seed Contractors
        log.info("Seeding Contractor applications...");
        User seededContractorUser = userRepository.findByPhone("+919876543211").orElse(null);
        contractorRepository.save(Contractor.builder()
                .fullName("John Contractor")
                .phone("+919876543211")
                .email("john@contractor.com")
                .userId(seededContractorUser != null ? seededContractorUser.getId() : null)
                .companyName("John Construction Co.")
                .gstNumber("29ABCDE1234F1Z5")
                .experience("12 years")
                .rating(4.7)
                .status("ACTIVE")
                .build());

        contractorRepository.save(Contractor.builder()
                .fullName("Beta Builders")
                .phone("+919811122341")
                .email("beta@builders.com")
                .companyName("Beta Builders Pvt Ltd")
                .gstNumber("29BBBBB2222B1Z2")
                .experience("3 years")
                .rating(4.0)
                .status("PENDING_VERIFICATION")
                .build());
        // Migration & Seeding for Products & Pricing Rules
        productRepository.findAll().forEach(p -> {
            String pName = p.getName() != null ? p.getName().toUpperCase() : "";
            String pCode = p.getCode() != null ? p.getCode().toUpperCase() : "";
            String pType = p.getProductType() != null ? p.getProductType().toUpperCase() : "";

            if (pName.contains("JCB") || pCode.contains("JCB") || pName.contains("EXCAVATOR") || pCode.contains("EXC") || "JCB".equals(pType)) {
                p.setProductType("HEAVY_EQUIPMENT");
                productRepository.save(p);
            } else if (pName.contains("BIKE") || pName.contains("AUTO") || pName.contains("CAR") || "BIKE_RIDE".equals(pType)) {
                p.setProductType("RIDE");
                productRepository.save(p);
            }
        });

        if (productRepository.count() == 0) {
            log.info("Seeding default products...");
            Product jcb = productRepository.save(Product.builder()
                    .name("JCB 3CX Backhoe Loader")
                    .code("JCB_3CX")
                    .productType("HEAVY_EQUIPMENT")
                    .category("HEAVY_EQUIPMENT")
                    .description("Heavy duty excavation & backhoe loading machine")
                    .active(true)
                    .build());

            Product bike = productRepository.save(Product.builder()
                    .name("Bike Taxi")
                    .code("BIKE")
                    .productType("RIDE")
                    .category("BIKE")
                    .description("Fast 2-wheeler city bike ride")
                    .active(true)
                    .build());
        }

        if (vehicleRepository.count() == 0) {
            log.info("Seeding initial DB vehicles...");
            User driverUser = userRepository.findByPhone("+919876543212").orElse(null);
            Long dUserId = driverUser != null ? driverUser.getId() : 3L;

            vehicleRepository.save(Vehicle.builder()
                    .userId(dUserId)
                    .driverId(1L)
                    .regNumber("MH 12 RE 1109")
                    .vehicleName("CAT 424B2 Backhoe")
                    .category("Machinery")
                    .machineryModel("CAT 424B2")
                    .machinerySubCategory("Backhoe")
                    .experience("1-3 years")
                    .mfgYear("2023")
                    .machineClass("CAT Heavy Backhoe")
                    .chassisVin("CAT424B2VIN99120")
                    .engineSerial("CAT-C4.4-4410")
                    .powertrain("DIESEL")
                    .status("APPROVED")
                    .isActive(true)
                    .fetchMode("AUTO_VAHAN")
                    .isVahanSynced(true)
                    .submittedOn("26/09/2026")
                    .vehicleDescription("Caterpillar 424B2 General purpose backhoe loader.")
                    .driverName("Balaji E.")
                    .driverPhone("7074953565")
                    .rcFrontUrl("")
                    .rcBackUrl("")
                    .insuranceUrl("")
                    .frontPhotoUrl("")
                    .sidePhotoUrl("")
                    .rearPhotoUrl("")
                    .cabinPhotoUrl("")
                    .enginePlatePhotoUrl("")
                    .build());

            vehicleRepository.save(Vehicle.builder()
                    .userId(dUserId)
                    .driverId(1L)
                    .regNumber("TN 09 BK 8842")
                    .vehicleName("JCB 3DX EcoXcellence")
                    .category("Machinery")
                    .machineryModel("JCB 3DX")
                    .machinerySubCategory("Backhoe")
                    .experience("3-5 years")
                    .mfgYear("2022")
                    .machineClass("Backhoe Loader Heavy Class")
                    .chassisVin("JCB3DX2022CH89210")
                    .engineSerial("ENG-JCB-994821")
                    .powertrain("DIESEL")
                    .status("APPROVED")
                    .isActive(false)
                    .fetchMode("AUTO_VAHAN")
                    .isVahanSynced(true)
                    .submittedOn("27/09/2026")
                    .vehicleDescription("Heavy-duty JCB Backhoe Loader equipped with standard bucket and breaker attachments.")
                    .driverName("Balaji E.")
                    .driverPhone("7074953565")
                    .rcFrontUrl("")
                    .rcBackUrl("")
                    .insuranceUrl("")
                    .build());

            vehicleRepository.save(Vehicle.builder()
                    .userId(2L)
                    .driverId(2L)
                    .regNumber("KA 05 MN 4410")
                    .vehicleName("Tata Hitachi EX 200")
                    .category("Machinery")
                    .machineryModel("Tata Hitachi EX 200")
                    .machinerySubCategory("Excavator")
                    .experience("5-10 years")
                    .mfgYear("2021")
                    .machineClass("Hydraulic Excavator 20-Ton Class")
                    .chassisVin("TATAEX200CH77621")
                    .engineSerial("CUMMINS-6BT-8841")
                    .powertrain("DIESEL")
                    .status("APPROVED")
                    .isActive(true)
                    .fetchMode("MANUAL")
                    .isVahanSynced(false)
                    .submittedOn("25/09/2026")
                    .vehicleDescription("Tata Hitachi 20 Ton Excavator suitable for quarry, earthwork, and construction site levelling.")
                    .driverName("Suren Kumar")
                    .driverPhone("7575848698")
                    .rcFrontUrl("")
                    .rcBackUrl("")
                    .insuranceUrl("")
                    .build());
        }

        seedFigmaRideProductsAndPricing();

        log.info("System successfully seeded and ready!");
    }

    private void seedFigmaRideProductsAndPricing() {
        log.info("Ensuring dynamic ride products and pricings match Figma specification...");
        // 1. Auto - ₹143
        seedOrUpdateRideProduct("Auto", "PROD_AUTO", "AUTO", "Reliable 3-wheeler auto rickshaw", 143.0, 14.0);
        // 2. Bike - ₹98
        seedOrUpdateRideProduct("Bike", "PROD_BIKE", "BIKE", "Fastest two-wheeler city bike ride", 98.0, 9.0);
        // 3. Scooter - ₹100
        seedOrUpdateRideProduct("Scooter", "PROD_SCOOTER", "BIKE", "Easy city gearless scooter ride", 100.0, 9.5);
        // 4. Mini - ₹243
        seedOrUpdateRideProduct("Mini", "PROD_MINI", "HATCHBACK", "Affordable and compact 4-seater hatchback", 243.0, 16.0);
        // 5. Prime Sedan - ₹165
        seedOrUpdateRideProduct("Prime Sedan", "PROD_SEDAN", "SEDAN", "Spacious top-rated sedans with extra legroom", 165.0, 17.0);
        // 6. Prime SUV - ₹225
        seedOrUpdateRideProduct("Prime SUV", "PROD_SUV", "SUV", "Spacious 6-seater premium SUVs", 225.0, 21.0);

        // Seed Driver Naresh S (TS 34 T 7080, White Toyota Innova, rating 4.8) if missing
        if (driverRepository.findByPhone("+919811122399").isEmpty()) {
            Driver naresh = driverRepository.save(Driver.builder()
                    .fullName("Naresh S")
                    .phone("+919811122399")
                    .email("naresh.s@rentron.com")
                    .licenseNumber("DL-0420220078901")
                    .aadhaarNumber("667788990011")
                    .experience("7 years")
                    .rating(4.8)
                    .totalJobs(189)
                    .totalEarnings(24500.0)
                    .status("ACTIVE")
                    .isOnline(true)
                    .latitude(12.9226)
                    .longitude(80.1275)
                    .city("Chennai")
                    .selectedVehicleType("SUV")
                    .selectedMachineryModel("Toyota Innova")
                    .build());

            vehicleRepository.save(Vehicle.builder()
                    .driverId(naresh.getId())
                    .regNumber("TS 34 T 7080")
                    .vehicleName("White Toyota Innova")
                    .category("Car")
                    .machineryModel("Toyota Innova Crysta")
                    .status("APPROVED")
                    .isActive(true)
                    .driverName("Naresh S")
                    .driverPhone("+919811122399")
                    .powertrain("DIESEL")
                    .build());
            log.info("Seeded Figma mock driver Naresh S (TS 34 T 7080)");
        }
    }

    private void seedOrUpdateRideProduct(String name, String code, String category, String description, double price, double perKm) {
        Product product = productRepository.findByCode(code).orElse(null);
        if (product == null) {
            product = Product.builder()
                    .name(name)
                    .code(code)
                    .productType("RIDE")
                    .category(category)
                    .description(description)
                    .active(true)
                    .build();
            product = productRepository.save(product);
            log.info("Created ride product: {}", name);
        } else {
            product.setName(name);
            product.setCategory(category);
            product.setProductType("RIDE");
            product.setActive(true);
            product = productRepository.save(product);
        }

        Pricing pricing = pricingRepository.findByProductId(product.getId()).orElse(null);
        if (pricing == null) {
            pricing = Pricing.builder()
                    .productId(product.getId())
                    .pricingModel("RIDE")
                    .chargingMethod("HOURLY")
                    .basePrice(price)
                    .minimumFare(price)
                    .perKmPrice(perKm)
                    .bookingFee(18.0)
                    .taxPercentage(5.0)
                    .active(true)
                    .build();
            pricingRepository.save(pricing);
            log.info("Created pricing for {}: basePrice = {}", name, price);
        } else {
            pricing.setBasePrice(price);
            pricing.setMinimumFare(price);
            pricing.setPerKmPrice(perKm);
            pricing.setActive(true);
            pricingRepository.save(pricing);
        }
    }

    private Permission createPermission(String name, String code) {
        return Permission.builder()
                .permissionName(name)
                .permissionCode(code)
                .build();
    }

    private Menu createMenu(String name, String code, Menu parent, String path, String icon, int order) {
        return Menu.builder()
                .menuName(name)
                .menuCode(code)
                .parentMenu(parent)
                .routePath(path)
                .icon(icon)
                .displayOrder(order)
                .active(true)
                .build();
    }

    private void grantMenuAccess(Role role, Menu menu) {
        roleMenuAccessRepository.save(RoleMenuAccess.builder()
                .role(role)
                .menu(menu)
                .build());
    }

    private void grantPermissionAccess(Role role, Permission permission) {
        rolePermissionAccessRepository.save(RolePermissionAccess.builder()
                .role(role)
                .permission(permission)
                .build());
    }

    private void createSeedUser(String name, String phone, String email, String password, Role role, boolean verified, boolean active) {
        User user = User.builder()
                .fullName(name)
                .phone(phone)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .verified(verified)
                .active(active)
                .build();
        userRepository.save(user);
        log.info("Seeded User - Role: [{}], Phone: [{}], Password: [{}]", role, phone, password);
    }
}
