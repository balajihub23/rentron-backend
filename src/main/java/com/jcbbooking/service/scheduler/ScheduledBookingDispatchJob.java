package com.jcbbooking.service.scheduler;

import com.jcbbooking.service.BookingAssignmentService;
import com.jcbbooking.util.SpringContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@Slf4j
public class ScheduledBookingDispatchJob implements Job {

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long bookingId = context.getMergedJobDataMap().getLong("bookingId");
        int leadMinutes = context.getMergedJobDataMap().containsKey("leadMinutes")
                ? context.getMergedJobDataMap().getInt("leadMinutes")
                : 15;

        log.info("Quartz ScheduledBookingDispatchJob executing for booking ID {} (leadMinutes: {})", bookingId, leadMinutes);
        try {
            BookingAssignmentService service = SpringContextHolder.getBean(BookingAssignmentService.class);
            if (service != null) {
                service.dispatchScheduledBookingWithDriverAlerts(bookingId, leadMinutes);
            } else {
                log.error("SpringContextHolder returned null BookingAssignmentService! Cannot dispatch booking ID {}", bookingId);
            }
        } catch (Exception e) {
            log.error("Error executing ScheduledBookingDispatchJob for booking {}: {}", bookingId, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
