package com.example.recorder.repository;

import com.example.recorder.entity.SummaryEntity;
import com.example.recorder.entity.SummaryEntity.SummaryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository для управления суммаризациями.
 */
@Repository
public interface SummaryRepository extends JpaRepository<SummaryEntity, String> {
    
    /**
     * Поиск суммаризации по ID записи.
     */
    Optional<SummaryEntity> findByRecordingId(String recordingId);
    
    /**
     * Поиск суммаризаций по статусу.
     */
    List<SummaryEntity> findByStatus(SummaryStatus status);
    
    /**
     * Поиск неудачных суммаризаций для повторной попытки.
     */
    @Query("SELECT s FROM SummaryEntity s WHERE s.status = 'FAILED' AND s.retryCount < :maxRetries")
    List<SummaryEntity> findFailedForRetry(@Param("maxRetries") int maxRetries);
    
    /**
     * Проверка существования суммаризации по ID записи.
     */
    boolean existsByRecordingId(String recordingId);
    
    /**
     * Подсчёт суммаризаций по статусу.
     */
    long countByStatus(SummaryStatus status);
    
    /**
     * Получение завершённых суммаризаций.
     */
    @Query("SELECT s FROM SummaryEntity s WHERE s.status = 'COMPLETED' ORDER BY s.completedAt DESC")
    List<SummaryEntity> findCompletedSummaries();
}
