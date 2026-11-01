package com.jcbbooking.repository;

import com.jcbbooking.model.BookingAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingAssignmentRepository extends JpaRepository<BookingAssignment, Long> {
    List<BookingAssignment> findAllByBookingIdOrderByOfferedAtDesc(Long bookingId);
    Optional<BookingAssignment> findByBookingIdAndCandidateTypeAndCandidateIdAndStatus(
            Long bookingId, String candidateType, Long candidateId, String status);
    List<BookingAssignment> findAllByCandidateTypeAndCandidateIdAndStatusOrderByOfferedAtDesc(
            String candidateType, Long candidateId, String status);
    List<BookingAssignment> findAllByBookingIdAndCandidateTypeAndCandidateId(
            Long bookingId, String candidateType, Long candidateId);
    Optional<BookingAssignment> findFirstByBookingIdAndCandidateTypeAndCandidateIdAndStatus(
            Long bookingId, String candidateType, Long candidateId, String status);
    void deleteAllByBookingId(Long bookingId);
}
