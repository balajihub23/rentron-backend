package com.jcbbooking.controller;

import com.jcbbooking.model.Role;
import com.jcbbooking.model.User;
import com.jcbbooking.repository.UserRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.service.CustomerService;
import com.jcbbooking.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Slf4j
public class CustomerController {

    private final CustomerService customerService;
    private final UserRepository userRepository;
    private final com.jcbbooking.repository.AddressRepository addressRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<User>>> getAllCustomers() {
        log.info("REST request to get all customers");
        List<User> customers = customerService.getAllCustomers();
        return ResponseEntity.ok(ApiResponse.success("Customers retrieved successfully", customers));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> getMyProfile(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request for current authenticated customer profile");
        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }

        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            return ResponseEntity.status(401).body(ApiResponse.error("User account not found or deleted"));
        }
        return ResponseEntity.ok(ApiResponse.success("Customer profile retrieved", user));
    }

    @GetMapping("/me/addresses")
    public ResponseEntity<ApiResponse<List<com.jcbbooking.model.Address>>> getMyCustomerAddresses(@AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to get addresses for customer ID: {}", userDetails != null ? userDetails.getId() : "anonymous");
        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.ok(ApiResponse.success("Addresses retrieved", List.of()));
        }
        List<com.jcbbooking.model.Address> addresses = addressRepository.findAllByUserIdAndActiveTrue(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success("Addresses retrieved successfully", addresses));
    }

    @PostMapping("/me/addresses")
    @Transactional
    public ResponseEntity<ApiResponse<com.jcbbooking.model.Address>> saveCustomerAddress(
            @RequestBody com.jcbbooking.model.Address addressRequest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to save customer address with lat/lng: lat={}, lng={}", addressRequest.getLatitude(), addressRequest.getLongitude());
        
        Long userId = (userDetails != null && userDetails.getUser() != null) ? userDetails.getId() : 1L;

        String fullAddr = addressRequest.getAddressLine1();
        if (fullAddr == null || fullAddr.isEmpty()) {
            fullAddr = addressRequest.getLandmark();
        }

        com.jcbbooking.model.Address address = com.jcbbooking.model.Address.builder()
                .userId(userId)
                .addressType(addressRequest.getAddressType() != null ? addressRequest.getAddressType() : "SITE_LOCATION")
                .label(addressRequest.getLabel() != null ? addressRequest.getLabel() : "Site")
                .contactName(addressRequest.getContactName())
                .phone(addressRequest.getPhone())
                .addressLine1(fullAddr)
                .addressLine2(addressRequest.getAddressLine2())
                .city(addressRequest.getCity() != null ? addressRequest.getCity() : "Chennai")
                .state(addressRequest.getState() != null ? addressRequest.getState() : "Tamil Nadu")
                .postalCode(addressRequest.getPostalCode())
                .latitude(addressRequest.getLatitude())
                .longitude(addressRequest.getLongitude())
                .landmark(addressRequest.getLandmark())
                .isDefault(addressRequest.getIsDefault() != null ? addressRequest.getIsDefault() : false)
                .active(true)
                .build();

        com.jcbbooking.model.Address saved = addressRepository.save(address);
        log.info("Successfully saved address ID {} in customer_addresses with latitude {} and longitude {}", saved.getId(), saved.getLatitude(), saved.getLongitude());
        return ResponseEntity.ok(ApiResponse.success("Address saved successfully with location coordinates", saved));
    }

    @PutMapping("/me")
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<ApiResponse<User>> registerOrUpdateProfile(
            @RequestBody User customerRequest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        log.info("REST request to register or update customer profile: {}", customerRequest);

        User targetUser = null;

        if (userDetails != null && userDetails.getUser() != null) {
            targetUser = userRepository.findById(userDetails.getId()).orElse(null);
        }

        if (targetUser == null && customerRequest.getPhone() != null) {
            targetUser = userRepository.findByPhone(customerRequest.getPhone()).orElse(null);
        }

        if (targetUser == null) {
            // Create a brand new customer user in users table
            targetUser = User.builder()
                    .phone(customerRequest.getPhone())
                    .fullName(customerRequest.getFullName())
                    .email(customerRequest.getEmail())
                    .role(Role.CUSTOMER)
                    .verified(true)
                    .active(true)
                    .totalBookings(0)
                    .walletBalance(0.0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        } else {
            // Update existing user entry in users table
            if (customerRequest.getFullName() != null) targetUser.setFullName(customerRequest.getFullName());
            if (customerRequest.getEmail() != null) targetUser.setEmail(customerRequest.getEmail());
            if (customerRequest.getAddress() != null) targetUser.setAddress(customerRequest.getAddress());
            targetUser.setRole(Role.CUSTOMER);
            targetUser.setUpdatedAt(LocalDateTime.now());
        }

        User savedUser = userRepository.save(targetUser);
        log.info("Successfully saved customer in users table with ID: {}", savedUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Customer profile saved successfully", savedUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<User>> getCustomerById(@PathVariable Long id) {
        log.info("REST request to get customer by id: {}", id);
        User customer = customerService.getCustomerById(id);
        return ResponseEntity.ok(ApiResponse.success("Customer retrieved successfully", customer));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<User>> saveCustomer(@RequestBody User customer) {
        log.info("REST request to save/update customer: {}", customer);
        User saved = customerService.saveCustomer(customer);
        String message = (customer.getId() == null || customer.getId() == 0)
                ? "Customer created successfully"
                : "Customer updated successfully";
        return ResponseEntity.ok(ApiResponse.success(message, saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCustomerById(@PathVariable Long id) {
        log.info("REST request to delete customer by id: {}", id);
        customerService.deleteCustomerById(id);
        return ResponseEntity.ok(ApiResponse.success("Customer deleted successfully"));
    }
}
