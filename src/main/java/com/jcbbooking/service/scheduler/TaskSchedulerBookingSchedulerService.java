package com.jcbbooking.service.scheduler;

import com.jcbbooking.model.Booking;
import com.jcbbooking.repository.BookingRepository;
import com.jcbbooking.service.BookingAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@ConditionalOnMissingBean(QuartzBookingSchedulerService.class)
@Slf4j
public class TaskSchedulerBookingSchedulerService implements BookingSchedulerService {

    private final BookingRepository bookingRepository;
    private final BookingAssignmentService bookingAssignmentService;
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(4);
    private final Map<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public TaskSchedulerBookingSchedulerService(BookingRepository bookingRepository,
                                                BookingAssignmentService bookingAssignmentService) {
        this.bookingRepository = bookingRepository;
        this.bookingAssignmentService = bookingAssignmentService;
    }

    @Override
    public void scheduleScheduledBookingDispatch(Booking booking, int leadMinutes) {
        if (booking == null || booking.getId() == null) {
            log.warn("Cannot schedule dispatch for null booking");
            return;
        }

        cancelScheduledBookingJob(booking.getId());

        LocalDateTime pickupTime = booking.getScheduledPickupAt();
        if (pickupTime == null) {
            pickupTime = LocalDateTime.now();
        }

        LocalDateTime dispatchTime = pickupTime.minusMinutes(leadMinutes);
        LocalDateTime now = LocalDateTime.now();

        long delaySeconds = Duration.between(now, dispatchTime).toSeconds();
        if (delaySeconds < 5) {
            delaySeconds = 5;
        }

        log.info("Scheduling dispatch for booking {} in {} seconds (leadMinutes: {})",
                booking.getId(), delaySeconds, leadMinutes);

        Long bookingId = booking.getId();
        ScheduledFuture<?> future = executorService.schedule(() -> {
            try {
                log.info("Executing scheduled dispatch for booking ID {}", bookingId);
                bookingAssignmentService.dispatchScheduledBookingWithDriverAlerts(bookingId, leadMinutes);
            } catch (Exception e) {
                log.error("Error executing scheduled dispatch for booking {}: {}", bookingId, e.getMessage(), e);
            } finally {
                activeTasks.remove(bookingId);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        activeTasks.put(bookingId, future);
    }

    @Override
    public void cancelScheduledBookingJob(Long bookingId) {
        ScheduledFuture<?> task = activeTasks.remove(bookingId);
        if (task != null) {
            task.cancel(false);
            log.info("Cancelled scheduled dispatch task for booking ID {}", bookingId);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rescheduleActiveScheduledBookings() {
        try {
            log.info("Fallback scheduler: Scanning for pending SCHEDULED bookings...");
            List<Booking> scheduledBookings = bookingRepository.findAllByStatus("SCHEDULED");
            for (Booking b : scheduledBookings) {
                scheduleScheduledBookingDispatch(b, 15);
            }
            log.info("Fallback scheduler: Initialized {} scheduled bookings", scheduledBookings.size());
        } catch (Exception e) {
            log.error("Error recovering scheduled bookings on startup in fallback scheduler: {}", e.getMessage());
        }
    }
}
