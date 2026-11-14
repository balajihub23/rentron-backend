package com.jcbbooking.service.impl;

import com.jcbbooking.exception.OtpException;
import com.jcbbooking.model.OtpPurpose;
import com.jcbbooking.model.OtpVerification;
import com.jcbbooking.repository.OtpVerificationRepository;
import com.jcbbooking.service.OtpCryptoService;
import com.jcbbooking.service.OtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OtpServiceImpl implements OtpService {

    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpCryptoService otpCryptoService;
    private final com.jcbbooking.service.SmsService smsService;
    private final com.jcbbooking.websocket.WebSocketNotificationService webSocketNotificationService;
    private final Random random = new Random();

    @Override
    @Transactional
    public OtpVerification generateOtp(String phone, OtpPurpose purpose) {
        log.info("Generating OTP for phone: {}, purpose: {}", phone, purpose);

        // Check if there is a previous OTP record that is currently blocking the user
        Optional<OtpVerification> latestOpt = otpVerificationRepository.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose);
        if (latestOpt.isPresent()) {
            OtpVerification lastOtp = latestOpt.get();
            if (lastOtp.getBlockedUntil() != null && lastOtp.getBlockedUntil().isAfter(LocalDateTime.now())) {
                log.warn("Phone number {} is blocked from requesting OTPs until {}", phone, lastOtp.getBlockedUntil());
                throw new OtpException("OTP service is temporarily blocked for this phone due to too many failed attempts. Try again after " + lastOtp.getBlockedUntil());
            }
        }

        // Generate 6-digit random code
        String rawOtpCode = String.format("%06d", random.nextInt(900000) + 100000);
        String encryptedOtpCode = otpCryptoService.encrypt(rawOtpCode);

        OtpVerification otpVerification = OtpVerification.builder()
                .phone(phone)
                .otpCode(encryptedOtpCode)
                .purpose(purpose)
                .expiresAt(LocalDateTime.now().plusMinutes(5)) // Rule 5: 5 minutes expiry
                .verified(false)
                .attempts(0)
                .build();

        OtpVerification savedOtp = otpVerificationRepository.save(otpVerification);
        savedOtp.setRawCode(rawOtpCode);
        
        // Log the raw OTP for diagnostics
        log.info("--------------------------------------------------");
        log.info("AUTH OTP: Generated OTP code [{}] for phone [{}] with purpose [{}]", rawOtpCode, phone, purpose);
        log.info("--------------------------------------------------");

        // Broadcast OTP over WebSocket for live testing & UI auto-fill
        try {
            webSocketNotificationService.sendAuthOtpEvent(phone, rawOtpCode);
        } catch (Exception ex) {
            log.warn("Failed to broadcast WebSocket OTP for {}: {}", phone, ex.getMessage());
        }

        // Dispatch real SMS via Fast2SMS gateway if enabled
        try {
            boolean sent = smsService.sendOtpSms(phone, rawOtpCode);
            if (sent) {
                log.info("Fast2SMS successfully delivered OTP code to phone [{}]", phone);
            } else {
                log.info("Fast2SMS not dispatched (disabled in settings, missing key, or gateway returned false). Fallback mock code: [{}]", rawOtpCode);
            }
        } catch (Exception ex) {
            log.warn("Error calling Fast2SMS service for phone [{}]: {}", phone, ex.getMessage());
        }

        return savedOtp;
    }

    @Override
    @Transactional
    public boolean verifyOtp(String phone, String otpCode, OtpPurpose purpose) {
        log.info("Verifying OTP for phone: {}, purpose: {}", phone, purpose);

        OtpVerification otpVerification = otpVerificationRepository
                .findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .orElseThrow(() -> new OtpException("No OTP has been requested for this phone number and purpose"));

        LocalDateTime now = LocalDateTime.now();

        // Rule 7: Check if temporarily blocked
        if (otpVerification.getBlockedUntil() != null && otpVerification.getBlockedUntil().isAfter(now)) {
            throw new OtpException("Too many invalid attempts. Your OTP verification is blocked until " + otpVerification.getBlockedUntil());
        }

        if (otpVerification.getVerified()) {
            throw new OtpException("This OTP has already been verified and cannot be reused");
        }

        if (otpVerification.getExpiresAt().isBefore(now)) {
            throw new OtpException("This OTP has expired. Please request a new one");
        }

        // Rule 6: Check attempts
        if (otpVerification.getAttempts() >= 5) {
            throw new OtpException("OTP verification is blocked due to excessive failures. Please request a new OTP.");
        }

        // Compare using crypto service
        boolean isMatch = otpCryptoService.matches(otpCode, otpVerification.getOtpCode());

        if (isMatch) {
            otpVerification.setVerified(true);
            otpVerification.setAttempts(otpVerification.getAttempts() + 1);
            otpVerificationRepository.save(otpVerification);
            log.info("OTP verification successful for phone: {}", phone);
            return true;
        } else {
            int newAttempts = otpVerification.getAttempts() + 1;
            otpVerification.setAttempts(newAttempts);

            if (newAttempts >= 5) {
                // Rule 7: Block OTP retry temporarily after max attempts
                otpVerification.setBlockedUntil(now.plusMinutes(15));
                otpVerificationRepository.save(otpVerification);
                log.warn("Phone number {} blocked due to reaching max OTP attempts", phone);
                throw new OtpException("Invalid OTP code. Max attempts exceeded. This phone number has been temporarily blocked for 15 minutes");
            } else {
                otpVerificationRepository.save(otpVerification);
                throw new OtpException("Invalid OTP code. Remaining attempts: " + (5 - newAttempts));
            }
        }
    }
}
