package com.example.recorder.repository;

import com.example.recorder.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для управления устройствами.
 */
@Repository
public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {

    /**
     * Поиск устройства по логину.
     */
    List<DeviceEntity> findByLogin(String login);

    /**
     * Поиск устройства по логину (возвращает первый результат).
     */
    default Optional<DeviceEntity> findFirstByLogin(String login) {
        List<DeviceEntity> devices = findByLogin(login);
        return devices.isEmpty() ? Optional.empty() : Optional.of(devices.get(0));
    }

    /**
     * Проверка существования устройства по логину.
     */
    boolean existsByLogin(String login);

    /**
     * Поиск устройства по токену сессии.
     */
    Optional<DeviceEntity> findBySessionToken(String sessionToken);

    /**
     * Поиск всех устройств пользователя.
     */
    @Query("SELECT d FROM DeviceEntity d WHERE d.userId = :userId")
    List<DeviceEntity> findByUserId(@Param("userId") String userId);

    /**
     * Проверка существования устройств у пользователя.
     */
    boolean existsByUserId(String userId);
}
