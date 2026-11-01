package com.jcbbooking.service.scheduler;

import com.jcbbooking.model.Booking;

public interface BookingSchedulerService {

    /**
     * Schedules dispatch and driver alerts for a scheduled booking ahead of pickup time.
     *
     * @param booking the booking entity
     * @param leadMinutes minutes ahead of pickup time to alert drivers and dispatch
     */
    void scheduleScheduledBookingDispatch(Booking booking, int leadMinutes);

    /**
     * Cancels any scheduled dispatch job for a booking if it was cancelled or completed.
     *
     * @param bookingId the ID of the booking
     */
    void cancelScheduledBookingJob(Long bookingId);
}
