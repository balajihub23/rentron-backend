package com.jcbbooking.service.impl;

import com.jcbbooking.dto.*;
import com.jcbbooking.exception.AuthenticationException;
import com.jcbbooking.exception.OtpException;
import com.jcbbooking.exception.ResourceNotFoundException;
import com.jcbbooking.model.*;
import com.jcbbooking.repository.RefreshTokenRepository;
import com.jcbbooking.repository.UserRepository;
import com.jcbbooking.security.CustomUserDetails;
import com.jcbbooking.security.jwt.JwtTokenProvider;
import com.jcbbooking.service.AuthService;
import com.jcbbooking.service.MenuService;
import com.jcbbooking.service.OtpService;
import com.jcbbooking.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final OtpService otpService;
    private final MenuService menuService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final com.jcbbooking.repository.PartnerApprovalRepository partnerApprovalRepository;
    private final com.jcbbooking.repository.DriverRepository driverRepository;

    @Override
    @Transactional
    public String sendOtp(SendOtpRequest request) {
        log.info("Processing send-otp request for phone: {}", request.getPhone());
        
        OtpPurpose purpose;
        try {
            purpose = OtpPurpose.valueOf(request.getPurpose().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new OtpException("Invalid OTP purpose: " + request.getPurpose());
        }

        // If purpose is RESET_PASSWORD, check if user exists
        if (purpose == OtpPurpose.RESET_PASSWORD) {
            userRepository.findByPhone(request.getPhone())
                    .orElseThrow(() -> new ResourceNotFoundException("No registered account found with phone: " + request.getPhone()));
        }

        OtpVerification verification = otpService.generateOtp(request.getPhone(), purpose);
        return verification != null ? verification.getRawCode() : null;
    }

    @Override
    @Transactional
    public AuthResponse verifyOtpLogin(VerifyOtpRequest request) {
        log.info("Processing verify-otp login request for phone: {}", request.getPhone());
        
        if (!"LOGIN".equalsIgnoreCase(request.getPurpose())) {
            throw new AuthenticationException("Invalid purpose for login verification: " + request.getPurpose());
        }

        // 1. Verify OTP code
        otpService.verifyOtp(request.getPhone(), request.getOtpCode(), OtpPurpose.LOGIN);

        // 2. Fetch or Auto-Register User
        User user = userRepository.findByPhone(request.getPhone())
                .orElseGet(() -> autoRegisterUserForPhone(request.getPhone()));

        // 3. Validate user status and build session
        return generateUserSession(user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.info("Processing unified login request for type: {}", request.getLoginType());
        
        // 1. Validate deviceType payload
        String deviceType = request.getDeviceType();
        if (deviceType == null || deviceType.trim().isEmpty()) {
            throw new AuthenticationException("Device type (deviceType: WEB or MOBILE) is required");
        }
        deviceType = deviceType.toUpperCase().trim();
        if (!deviceType.equals("WEB") && !deviceType.equals("MOBILE")) {
            throw new AuthenticationException("Invalid device type. Allowed values: WEB, MOBILE");
        }

        User user;
        String type = request.getLoginType().toUpperCase();

        switch (type) {
            case "PHONE_PASSWORD" -> {
                if (request.getPhone() == null || request.getPassword() == null) {
                    throw new AuthenticationException("Phone and password are required for PHONE_PASSWORD login");
                }
                user = userRepository.findByPhone(request.getPhone())
                        .orElseThrow(() -> new AuthenticationException("Invalid phone number or password"));
                
                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    throw new AuthenticationException("Invalid phone number or password");
                }
            }
            case "EMAIL_PASSWORD" -> {
                if (request.getEmail() == null || request.getPassword() == null) {
                    throw new AuthenticationException("Email and password are required for EMAIL_PASSWORD login");
                }
                user = userRepository.findByEmail(request.getEmail())
                        .orElseThrow(() -> new AuthenticationException("Invalid email address or password"));

                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    throw new AuthenticationException("Invalid email address or password");
                }
            }
            case "PHONE_OTP" -> {
                if (request.getPhone() == null || request.getOtp() == null) {
                    throw new AuthenticationException("Phone and OTP are required for PHONE_OTP login");
                }
                // Verify OTP
                otpService.verifyOtp(request.getPhone(), request.getOtp(), OtpPurpose.LOGIN);
                Role targetRole = "CUSTOMER".equalsIgnoreCase(request.getRole()) ? Role.CUSTOMER : Role.DRIVER;
                user = userRepository.findByPhone(request.getPhone())
                        .orElseGet(() -> autoRegisterUserForPhone(request.getPhone(), targetRole));
            }
            default -> throw new AuthenticationException("Unsupported login type: " + request.getLoginType());
        }

        // 2. Enforce Device Type Restrictions based on User Role (Cross-Role Device Lockout)
        Role role = user.getRole();
        if (role == Role.ADMIN && !deviceType.equals("WEB")) {
            throw new AuthenticationException("Access denied: Administrator accounts are restricted to WEB client logins only");
        }
        if (role == Role.CONTRACTOR && !deviceType.equals("WEB")) {
            throw new AuthenticationException("Access denied: Contractor accounts are restricted to WEB client logins only");
        }
        if (role == Role.DRIVER && !deviceType.equals("MOBILE")) {
            throw new AuthenticationException("Access denied: Driver accounts are restricted to MOBILE app logins only");
        }

        return generateUserSession(user);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        log.info("Processing refresh token request");
        
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new AuthenticationException("Invalid or revoked refresh token"));

        if (refreshToken.getRevoked()) {
            throw new AuthenticationException("This refresh token has been revoked");
        }

        if (refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new AuthenticationException("Refresh token has expired. Please log in again");
        }

        User user = refreshToken.getUser();
        
        // Ensure user is still active and valid
        validateUserStatus(user);

        // Delete old token (Token rotation policy)
        refreshTokenRepository.delete(refreshToken);

        return createSessionAndResponse(user);
    }

    @Override
    @Transactional
    public void logout(String refreshTokenValue) {
        log.info("Processing logout request");
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(token -> {
                    log.info("Revoking refresh token for user ID: {}", token.getUser().getId());
                    refreshTokenRepository.delete(token);
                });
    }

    private AuthResponse generateUserSession(User user) {
        validateUserStatus(user);
        
        // Update last login timestamp
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return createSessionAndResponse(user);
    }

    private User autoRegisterUserForPhone(String phone) {
        return autoRegisterUserForPhone(phone, Role.DRIVER);
    }

    private User autoRegisterUserForPhone(String phone, Role targetRole) {
        log.info("Auto-registering new user for phone: {} with role: {}", phone, targetRole);

        String cleanPhone = phone != null ? phone.replaceAll("[^0-9]", "") : "";
        if (cleanPhone.length() > 10) cleanPhone = cleanPhone.substring(cleanPhone.length() - 10);
        final String last10 = cleanPhone;

        // 1. Check if user already exists (by exact phone or last 10 digits match)
        User existingUser = userRepository.findByPhone(phone)
                .orElseGet(() -> userRepository.findAll().stream()
                        .filter(u -> u.getPhone() != null && !last10.isEmpty() && u.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                        .findFirst().orElse(null));

        if (existingUser != null) {
            log.info("Found existing user ID {} for phone {}", existingUser.getId(), phone);
            return existingUser;
        }

        Role userRole = targetRole != null ? targetRole : Role.DRIVER;
        String name = userRole == Role.CUSTOMER ? "Customer" : "Driver";
        if (!cleanPhone.isEmpty()) {
            name += " (" + (cleanPhone.length() >= 4 ? cleanPhone.substring(cleanPhone.length() - 4) : cleanPhone) + ")";
        }

        String email = null;

        com.jcbbooking.model.Driver driver = null;
        if (userRole == Role.DRIVER) {
            com.jcbbooking.model.PartnerApproval pa = partnerApprovalRepository.findByPhone(phone)
                    .orElseGet(() -> partnerApprovalRepository.findAll().stream()
                            .filter(p -> p.getPhone() != null && !last10.isEmpty() && p.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                            .findFirst().orElse(null));

            driver = driverRepository.findByPhone(phone)
                    .orElseGet(() -> driverRepository.findAll().stream()
                            .filter(d -> d.getPhone() != null && !last10.isEmpty() && d.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                            .findFirst().orElse(null));

            if (pa != null) {
                if (pa.getFullName() != null && !pa.getFullName().isEmpty()) name = pa.getFullName();
                if (pa.getEmail() != null && !pa.getEmail().isEmpty()) email = pa.getEmail();
            } else if (driver != null) {
                if (driver.getFullName() != null && !driver.getFullName().isEmpty()) name = driver.getFullName();
                if (driver.getEmail() != null && !driver.getEmail().isEmpty()) email = driver.getEmail();
            }
        }

        User newUser = User.builder()
                .phone(phone)
                .fullName(name)
                .email(email)
                .role(userRole)
                .verified(true)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        User savedUser;
        try {
            savedUser = userRepository.save(newUser);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            log.warn("DataIntegrityViolationException caught during auto-registration for phone: {}. Concurrent registration detected, retrieving saved user.", phone);
            savedUser = userRepository.findByPhone(phone)
                    .orElseGet(() -> userRepository.findAll().stream()
                            .filter(u -> u.getPhone() != null && !last10.isEmpty() && u.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                            .findFirst()
                            .orElseThrow(() -> ex));
        }

        // Update driver/partnerApproval userId reference if exists
        if (driver != null && driver.getUserId() == null) {
            driver.setUserId(savedUser.getId());
            driverRepository.save(driver);
        }

        return savedUser;
    }

    private void validateUserStatus(User user) {
        if (user == null || user.getPhone() == null) return;
        
        String phone = user.getPhone();
        String cleanPhone = phone.replaceAll("[^0-9]", "");
        if (cleanPhone.length() > 10) cleanPhone = cleanPhone.substring(cleanPhone.length() - 10);

        final String last10 = cleanPhone;

        String status = partnerApprovalRepository.findByPhone(phone)
                .map(com.jcbbooking.model.PartnerApproval::getStatus)
                .orElseGet(() -> driverRepository.findByPhone(phone)
                        .map(com.jcbbooking.model.Driver::getStatus)
                        .orElseGet(() -> partnerApprovalRepository.findAll().stream()
                                .filter(pa -> pa.getPhone() != null && pa.getPhone().replaceAll("[^0-9]", "").endsWith(last10))
                                .map(com.jcbbooking.model.PartnerApproval::getStatus)
                                .findFirst()
                                .orElse(user.getActive() != null && user.getActive() ? "ACTIVE" : "PENDING_VERIFICATION")));

        if ("REJECTED".equalsIgnoreCase(status)) {
            log.warn("Login blocked: Partner application rejected for phone: {}", phone);
            throw new AuthenticationException("Your partner application has been rejected by Admin. Please contact support.");
        }

        if ("SUSPENDED".equalsIgnoreCase(status)) {
            log.warn("Login blocked: User account suspended for phone: {}", phone);
            throw new AuthenticationException("Your account has been suspended by Admin. Please contact support.");
        }
    }

    private AuthResponse createSessionAndResponse(User user) {
        // Fetch permissions and menus
        List<String> permissions = menuService.getPermissionsForRole(user.getRole());
        List<MenuResponse> menus = menuService.getMenusForRole(user.getRole());

        // Construct UserDetails
        CustomUserDetails userDetails = new CustomUserDetails(user, permissions);

        // Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);
        
        // Rule: Refresh Token stored in DB, 100 years expiry (effectively never expires)
        String refreshTokenValue = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenValue)
                .expiryDate(LocalDateTime.now().plusYears(100))
                .revoked(false)
                .build();
        
        refreshTokenRepository.save(refreshToken);

        // Build response DTOs
        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .role(user.getRole())
                .verified(user.getVerified())
                .active(user.getActive())
                .build();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenValue)
                .user(userDto)
                .role(user.getRole())
                .permissions(permissions)
                .menus(menus)
                .build();
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        log.info("Processing reset password request for phone: {}", request.getPhone());
        
        // 1. Verify OTP code
        otpService.verifyOtp(request.getPhone(), request.getOtpCode(), OtpPurpose.RESET_PASSWORD);
        
        // 2. Fetch User
        User user = userRepository.findByPhone(request.getPhone())
                .orElseThrow(() -> new AuthenticationException("No registered account found with phone: " + request.getPhone()));
        
        // 3. Re-encrypt and update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        
        log.info("Password successfully reset for phone: {}", request.getPhone());
    }
}
