package com.example.recorder.repository;

import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.RecordingEntity.RecordingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository для управления аудио записями.
 * Предоставляет CRUD операции и методы поиска.
 */
@Repository
public interface RecordingRepository extends JpaRepository<RecordingEntity, String> {
    
    /**
     * Поиск записей по статусу.
     */
    Page<RecordingEntity> findByStatus(RecordingStatus status, Pageable pageable);
    
    /**
     * Поиск записей по устройству.
     */
    Page<RecordingEntity> findByDeviceInfo(String deviceInfo, Pageable pageable);
    
    /**
     * Поиск записей по диапазону дат.
     */
    @Query("SELECT r FROM RecordingEntity r WHERE r.createdAt BETWEEN :from AND :to ORDER BY r.createdAt DESC")
    Page<RecordingEntity> findByCreatedAtBetween(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );
    
    /**
     * Поиск записей, ожидающих суммаризации.
     */
    @Query("SELECT r FROM RecordingEntity r WHERE r.status = 'SUMMARIZING' OR r.status = 'UPLOADED'")
    List<RecordingEntity> findPendingSummarization();
    
    /**
     * Проверка существования файла по имени.
     */
    boolean existsByFilename(String filename);
    
    /**
     * Поиск записи по оригинальному имени файла.
     */
    Optional<RecordingEntity> findByOriginalFilename(String originalFilename);
    
    /**
     * Подсчёт записей по статусу.
     */
    long countByStatus(RecordingStatus status);
    
    /**
     * Получение последних записей.
     */
    @Query("SELECT r FROM RecordingEntity r ORDER BY r.createdAt DESC")
    Page<RecordingEntity> findLatest(Pageable pageable);
    
    /**
     * Удаление записей по статусу FAILED (для очистки).
     */
    void deleteByStatus(RecordingStatus status);
}
