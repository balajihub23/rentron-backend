package com.jcbbooking.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcbbooking.model.BookingSetting;
import com.jcbbooking.repository.BookingSettingRepository;
import com.jcbbooking.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class Fast2SmsServiceImpl implements SmsService {

    private final BookingSettingRepository bookingSettingRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean sendOtpSms(String phone, String otpCode) {
        BookingSetting settings = bookingSettingRepository.findBySettingKey("DEFAULT_SETTINGS").orElse(null);
        if (settings == null) {
            log.warn("Cannot send SMS: DEFAULT_SETTINGS not found in database.");
            return false;
        }

        if (Boolean.FALSE.equals(settings.getSmsEnabled())) {
            log.info("SMS sending is disabled in booking settings (smsEnabled=false). Skipping Fast2SMS dispatch.");
            return false;
        }

        String apiKey = settings.getFast2smsApiKey();
        if (apiKey == null || apiKey.trim().isEmpty() || "******".equals(apiKey)) {
            log.warn("Fast2SMS API key is not configured or is masked. Skipping SMS dispatch.");
            return false;
        }

        String templateId = settings.getOtpMessageTemplate();
        if (templateId == null || templateId.trim().isEmpty()) {
            templateId = "214426"; // Fallback default DLT template
        }

        return sendSmsInternal(settings, phone, templateId, otpCode);
    }

    @Override
    public boolean sendSms(String phone, String templateId, String variablesValues) {
        BookingSetting settings = bookingSettingRepository.findBySettingKey("DEFAULT_SETTINGS").orElse(null);
        if (settings == null || Boolean.FALSE.equals(settings.getSmsEnabled())) {
            return false;
        }
        return sendSmsInternal(settings, phone, templateId, variablesValues);
    }

    private boolean sendSmsInternal(BookingSetting settings, String phone, String messageTemplate, String variablesValues) {
        try {
            // Clean phone number to 10 digits
            String cleanPhone = phone.replaceAll("[^0-9]", "");
            if (cleanPhone.length() > 10 && cleanPhone.startsWith("91")) {
                cleanPhone = cleanPhone.substring(2);
            } else if (cleanPhone.length() == 11 && cleanPhone.startsWith("0")) {
                cleanPhone = cleanPhone.substring(1);
            }

            if (cleanPhone.length() != 10) {
                log.warn("Invalid phone number format for Fast2SMS: {}", phone);
                return false;
            }

            String url = settings.getFast2smsUrl();
            if (url == null || url.trim().isEmpty()) {
                url = "https://www.fast2sms.com/dev/bulkV2";
            }
            if (url.endsWith("?")) {
                url = url.substring(0, url.length() - 1);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            headers.set("authorization", settings.getFast2smsApiKey());

            String route = settings.getFast2smsRoute() != null ? settings.getFast2smsRoute().toLowerCase() : "dlt";

            Map<String, Object> body = new HashMap<>();
            body.put("route", route);
            body.put("numbers", cleanPhone);

            if ("dlt".equals(route)) {
                String senderId = settings.getFast2smsSenderId();
                if (senderId == null || senderId.trim().isEmpty()) {
                    senderId = "RENTRON";
                }
                body.put("sender_id", senderId);
                body.put("message", messageTemplate);
                body.put("variables_values", variablesValues);
            } else if ("otp".equals(route)) {
                body.put("variables_values", variablesValues);
            } else {
                // v3 or quick transactional route
                String senderId = settings.getFast2smsSenderId();
                if (senderId == null || senderId.trim().isEmpty()) {
                    senderId = "TXTIND";
                }
                body.put("sender_id", senderId);
                body.put("message", messageTemplate);
            }

            log.info("Dispatching Fast2SMS payload to {}: route={}, numbers={}, sender_id={}, template={}",
                    url, route, cleanPhone, body.get("sender_id"), messageTemplate);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            log.info("Fast2SMS Response Status: {}, Body: {}", response.getStatusCode(), response.getBody());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                boolean isSuccess = root.path("return").asBoolean(false);
                if (isSuccess) {
                    log.info("Fast2SMS delivered successfully to {}. Request ID: {}", cleanPhone, root.path("request_id").asText());
                    return true;
                } else {
                    log.warn("Fast2SMS returned failure response: {}", root.path("message").toString());
                }
            }
        } catch (Exception e) {
            log.error("Failed to send SMS via Fast2SMS: {}", e.getMessage(), e);
        }
        return false;
    }
}
