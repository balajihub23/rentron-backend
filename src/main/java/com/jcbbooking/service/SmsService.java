package com.jcbbooking.service;

public interface SmsService {
    boolean sendOtpSms(String phone, String otpCode);
    boolean sendSms(String phone, String templateId, String variablesValues);
}
