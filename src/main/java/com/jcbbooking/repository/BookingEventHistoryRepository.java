package com.jcbbooking.repository;

import com.jcbbooking.model.BookingEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingEventHistoryRepository extends JpaRepository<BookingEventHistory, Long> {
    List<BookingEventHistory> findAllByBookingIdOrderByCreatedAtAsc(Long bookingId);
    void deleteAllByBookingId(Long bookingId);
}
