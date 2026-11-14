package com.jcbbooking.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "booking_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingSetting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "setting_key", unique = true, nullable = false, length = 50)
	private String settingKey; // e.g. DEFAULT_SETTINGS

	// --- Payment Configuration ---
	@Column(name = "key_id", length = 150)
	private String keyId;

	@Column(name = "key_secret", length = 150)
	private String keySecret;

	@Builder.Default
	@Column(name = "currency", length = 10)
	private String currency = "INR";

	// --- Auto-Assignment Configuration ---
	@Builder.Default
	@Column(name = "assignment_type", nullable = false, length = 30)
	private String assignmentType = "AUTO"; // AUTO, MANUAL

	@Builder.Default
	@Column(name = "initial_radius_km", nullable = false)
	private Double initialRadiusKm = 5.0;

	@Builder.Default
	@Column(name = "radius_increment_km", nullable = false)
	private Double radiusIncrementKm = 5.0;

	@Builder.Default
	@Column(name = "max_radius_km", nullable = false)
	private Double maxRadiusKm = 20.0;

	@Builder.Default
	@Column(name = "assignment_timeout_seconds", nullable = false)
	private Integer assignmentTimeoutSeconds = 30;

	@Builder.Default
	@Column(name = "max_location_age_seconds", nullable = false)
	private Integer maxLocationAgeSeconds = 300; // 5 mins

	@Builder.Default
	@Column(name = "notify_admin_when_unassigned", nullable = false)
	private Boolean notifyAdminWhenUnassigned = true;

	@Builder.Default
	@Column(name = "dispatch_lead_time_minutes", nullable = false)
	private Integer dispatchLeadTimeMinutes = 30;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
	private LocalDateTime updatedAt;

	@Column(name = "map_key")
	private String mapKey;

	@Builder.Default
	@Column(name = "support_phone", length = 30)
	private String supportPhone = "+91 98765 43210";

	// --- Push Notification Configuration (FCM) ---
	@Column(name = "fcm_server_key", length = 500)
	private String fcmServerKey;

	@Column(name = "fcm_project_id", length = 150)
	private String fcmProjectId;

	@Builder.Default
	@Column(name = "push_enabled", nullable = false)
	private Boolean pushEnabled = true;

	// --- Email SMTP Configuration ---
	@Column(name = "smtp_host", length = 150)
	private String smtpHost;

	@Builder.Default
	@Column(name = "smtp_port")
	private Integer smtpPort = 587;

	@Column(name = "smtp_username", length = 150)
	private String smtpUsername;

	@Column(name = "smtp_password", length = 150)
	private String smtpPassword;

	@Column(name = "smtp_from_email", length = 150)
	private String smtpFromEmail;

	@Builder.Default
	@Column(name = "smtp_enabled", nullable = false)
	private Boolean smtpEnabled = false;

	// --- Fast2SMS Configuration ---
	@Builder.Default
	@Column(name = "fast2sms_url", length = 200)
	private String fast2smsUrl = "https://www.fast2sms.com/dev/bulkV2";

	@Column(name = "fast2sms_api_key", length = 200)
	private String fast2smsApiKey;

	@Builder.Default
	@Column(name = "fast2sms_sender_id", length = 50)
	private String fast2smsSenderId = "RENTRON";

	@Builder.Default
	@Column(name = "fast2sms_route", length = 20)
	private String fast2smsRoute = "dlt";

	@Builder.Default
	@Column(name = "otp_message_template", length = 100)
	private String otpMessageTemplate = "214426";

	@Builder.Default
	@Column(name = "otp_digit")
	private Integer otpDigit = 6;

	@Builder.Default
	@Column(name = "sms_vendor", length = 50)
	private String smsVendor = "FAST-SMS";

	@Builder.Default
	@Column(name = "sms_enabled", nullable = false)
	private Boolean smsEnabled = false;
}
