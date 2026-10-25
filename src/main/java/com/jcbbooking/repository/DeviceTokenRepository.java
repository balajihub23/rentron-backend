package com.jcbbooking.repository;

import com.jcbbooking.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    List<DeviceToken> findAllByUserIdAndActiveTrue(Long userId);
    List<DeviceToken> findAllByUserId(Long userId);
    Optional<DeviceToken> findByUserIdAndDeviceToken(Long userId, String deviceToken);
    void deleteAllByDeviceToken(String deviceToken);
}
