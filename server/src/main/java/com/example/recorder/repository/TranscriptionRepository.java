package com.example.recorder.repository;

import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.TranscriptionEntity.TranscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Репозиторий для работы с текстовыми расшифровками.
 */
@Repository
public interface TranscriptionRepository extends JpaRepository<TranscriptionEntity, String> {
    
    /**
     * Найти расшифровку по ID записи.
     */
    Optional<TranscriptionEntity> findByRecordingId(String recordingId);
    
    /**
     * Проверить, существует ли расшифровка для записи.
     */
    boolean existsByRecordingId(String recordingId);
    
    /**
     * Найти расшифровку по ID записи со статусом COMPLETED.
     */
    @Query("SELECT t FROM TranscriptionEntity t WHERE t.recording.id = :recordingId AND t.status = 'COMPLETED'")
    Optional<TranscriptionEntity> findCompletedByRecordingId(@Param("recordingId") String recordingId);
}