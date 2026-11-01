package com.jcbbooking.service.scheduler;

import com.jcbbooking.model.Booking;
import com.jcbbooking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

@Service
@Primary
@ConditionalOnClass(name = "org.quartz.Scheduler")
@RequiredArgsConstructor
@Slf4j
public class QuartzBookingSchedulerService implements BookingSchedulerService {

    private final Scheduler scheduler;
    private final BookingRepository bookingRepository;

    public static final String JOB_GROUP = "SCHEDULED_BOOKINGS_GROUP";

    /**
     * Schedules a Quartz job to trigger dispatch and intimate drivers leadMinutes before pickup.
     */
    @Override
    public void scheduleScheduledBookingDispatch(Booking booking, int leadMinutes) {
        if (booking == null || booking.getId() == null) {
            log.warn("Cannot schedule Quartz dispatch for null booking");
            return;
        }

        LocalDateTime pickupTime = booking.getScheduledPickupAt();
        if (pickupTime == null) {
            log.warn("Booking {} has no scheduledPickupAt. Using now.", booking.getId());
            pickupTime = LocalDateTime.now();
        }

        LocalDateTime dispatchTime = pickupTime.minusMinutes(leadMinutes);
        LocalDateTime now = LocalDateTime.now();

        Date triggerStartTime;
        if (dispatchTime.isBefore(now.plusSeconds(30))) {
            triggerStartTime = Date.from(now.plusSeconds(5).atZone(ZoneId.systemDefault()).toInstant());
            log.info("Dispatch time {} for booking {} is immediate or in past. Triggering in 5s.", dispatchTime, booking.getId());
        } else {
            triggerStartTime = Date.from(dispatchTime.atZone(ZoneId.systemDefault()).toInstant());
            log.info("Scheduling Quartz job for booking {} at {} ({} mins before pickup at {})",
                    booking.getId(), dispatchTime, leadMinutes, pickupTime);
        }

        JobKey jobKey = new JobKey("scheduled_booking_" + booking.getId(), JOB_GROUP);
        TriggerKey triggerKey = new TriggerKey("trigger_booking_" + booking.getId(), JOB_GROUP);

        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }

            JobDetail jobDetail = JobBuilder.newJob(ScheduledBookingDispatchJob.class)
                    .withIdentity(jobKey)
                    .usingJobData("bookingId", booking.getId())
                    .usingJobData("leadMinutes", leadMinutes)
                    .storeDurably(false)
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .startAt(triggerStartTime)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Successfully registered Quartz trigger for booking ID {} at {}", booking.getId(), triggerStartTime);
        } catch (SchedulerException e) {
            log.error("Failed to register Quartz trigger for booking {}: {}", booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Cancels scheduled Quartz job if booking is cancelled.
     */
    @Override
    public void cancelScheduledBookingJob(Long bookingId) {
        JobKey jobKey = new JobKey("scheduled_booking_" + bookingId, JOB_GROUP);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Cancelled Quartz job for booking ID {}", bookingId);
            }
        } catch (SchedulerException e) {
            log.error("Failed to cancel Quartz job for booking {}: {}", bookingId, e.getMessage());
        }
    }

    /**
     * Automatically reschedule pending SCHEDULED bookings on backend startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rescheduleActiveScheduledBookings() {
        try {
            log.info("Scanning for pending SCHEDULED bookings to register with Quartz...");
            List<Booking> scheduledBookings = bookingRepository.findAllByStatus("SCHEDULED");
            for (Booking b : scheduledBookings) {
                scheduleScheduledBookingDispatch(b, 15);
            }
            log.info("Completed Quartz initialization for {} scheduled bookings", scheduledBookings.size());
        } catch (Exception e) {
            log.error("Error recovering scheduled bookings on startup: {}", e.getMessage());
        }
    }
}
