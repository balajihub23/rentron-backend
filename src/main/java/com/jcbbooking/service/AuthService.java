package com.jcbbooking.service;

import com.jcbbooking.dto.*;

public interface AuthService {

    String sendOtp(SendOtpRequest request);

    AuthResponse verifyOtpLogin(VerifyOtpRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refreshToken(RefreshTokenRequest request);

    void logout(String refreshToken);

    void resetPassword(ResetPasswordRequest request);
}
