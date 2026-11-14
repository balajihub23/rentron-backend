package com.jcbbooking.controller;

import com.jcbbooking.dto.*;
import com.jcbbooking.service.AuthService;
import com.jcbbooking.util.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        log.info("REST request to send OTP to: {}", request.getPhone());
        String rawOtp = authService.sendOtp(request);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("phone", request.getPhone());
        data.put("testOtp", rawOtp);
        data.put("message", "OTP generated and broadcast via WebSocket");
        return ResponseEntity.ok(ApiResponse.success("OTP sent successfully", data));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request,
            @RequestHeader(value = "X-Client-App-Key", required = false) String appKey) {
        log.info("REST request to verify OTP login for phone: {}", request.getPhone());
        
        String expectedWebKey = "rentron-web-admin-secret-key-108F9";
        String expectedMobileKey = "rentron-driver-mobile-app-secret-key-294AB";
        String expectedCustomerKey = "rentron-customer-mobile-app-secret-key-492BC";
        
        if (appKey != null && !appKey.trim().isEmpty()) {
            if (!appKey.equals(expectedWebKey) && !appKey.equals(expectedMobileKey) && !appKey.equals(expectedCustomerKey)) {
                log.warn("Unverified API Request: Invalid X-Client-App-Key header on verify-otp: [{}]", appKey);
                throw new com.jcbbooking.exception.AuthenticationException("Access denied: Invalid X-Client-App-Key client app signature.");
            }
        } else {
            log.warn("API Request missing X-Client-App-Key header on verify-otp, proceeding with request validation.");
        }
        
        AuthResponse response = authService.verifyOtpLogin(request);
        return ResponseEntity.ok(ApiResponse.success("OTP verified and login successful", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Client-App-Key", required = false) String appKey) {
        log.info("REST request for unified login of type: {}", request.getLoginType());
        
        String expectedWebKey = "rentron-web-admin-secret-key-108F9";
        String expectedMobileKey = "rentron-driver-mobile-app-secret-key-294AB";
        String expectedCustomerKey = "rentron-customer-mobile-app-secret-key-492BC";
        
        if (appKey != null && !appKey.trim().isEmpty()) {
            if (!appKey.equals(expectedWebKey) && !appKey.equals(expectedMobileKey) && !appKey.equals(expectedCustomerKey)) {
                log.warn("Unverified API Request: Invalid X-Client-App-Key header on login: [{}]", appKey);
                throw new com.jcbbooking.exception.AuthenticationException("Access denied: Invalid X-Client-App-Key client app signature.");
            }
        } else {
            log.warn("API Request missing X-Client-App-Key header on login, proceeding with request validation.");
        }
        
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("REST request to refresh token");
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("REST request to log out and revoke refresh token");
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            @RequestHeader(value = "X-Client-App-Key", required = false) String appKey) {
        log.info("REST request to reset password for phone: {}", request.getPhone());
        
        String expectedWebKey = "rentron-web-admin-secret-key-108F9";
        String expectedMobileKey = "rentron-driver-mobile-app-secret-key-294AB";
        String expectedCustomerKey = "rentron-customer-mobile-app-secret-key-492BC";
        
        if (appKey != null && !appKey.trim().isEmpty()) {
            if (!appKey.equals(expectedWebKey) && !appKey.equals(expectedMobileKey) && !appKey.equals(expectedCustomerKey)) {
                log.warn("Unverified API Request: Invalid X-Client-App-Key header on reset-password: [{}]", appKey);
                throw new com.jcbbooking.exception.AuthenticationException("Access denied: Invalid X-Client-App-Key client app signature.");
            }
        } else {
            log.warn("API Request missing X-Client-App-Key header on reset-password, proceeding with request validation.");
        }
        
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Password has been reset successfully"));
    }
}
