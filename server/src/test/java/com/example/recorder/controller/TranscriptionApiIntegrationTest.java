package com.example.recorder.controller;

import com.example.recorder.auth.DeviceAuthService;
import com.example.recorder.entity.RecordingEntity;
import com.example.recorder.entity.TranscriptionEntity;
import com.example.recorder.entity.UserEntity;
import com.example.recorder.repository.RecordingRepository;
import com.example.recorder.repository.TranscriptionRepository;
import com.example.recorder.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты API расшифровки аудио.
 * Тестируют полный цикл: загрузка → расшифровка → получение результата.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Интеграционные тесты API расшифровки")
class TranscriptionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private TranscriptionRepository transcriptionRepository;

    @Autowired
    private DeviceAuthService deviceAuthService;

    private UserEntity testUser;
    private String authToken;

    @BeforeEach
    void setUp() {
        // Создаём тестового пользователя
        testUser = new UserEntity();
        testUser.setTelegramId(123456789L);
        testUser.setUsername("test_user");
        testUser.setLogin("test_login");
        testUser.setPasswordHash("test_password_hash");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setIsActive(true);
        userRepository.save(testUser);

        // Аутентифицируем устройство и получаем токен
        DeviceAuthService.AuthenticationResult authResult = deviceAuthService.authenticate(
            "test_login",
            "test_password_hash"
        );
        authToken = authResult.token();
    }

    @Test
    @DisplayName("Полный цикл: загрузка аудио → запуск расшифровки → получение результата")
    void testFullTranscriptionCycle() throws Exception {
        // Создаём тестовый WAV файл
        File tempWavFile = createTestWavFile();

        // 1. Загружаем аудио
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.wav",
            "audio/wav",
            Files.readAllBytes(tempWavFile.toPath())
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/bot/recordings/upload")
                .file(file)
                .param("deviceInfo", "test-device")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isCreated())
            .andReturn();

        // Извлекаем ID записи из ответа
        String jsonResponse = uploadResult.getResponse().getContentAsString();
        String recordingId = extractRecordingId(jsonResponse);
        assertNotNull(recordingId, "ID записи не найден в ответе");

        // 2. Запускаем расшифровку
        mockMvc.perform(post("/api/v1/bot/recordings/{id}/transcribe", recordingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.message").value("Расшифровка запущена"));

        // 3. Проверяем статус расшифровки (симуляция завершается быстро)
        Thread.sleep(3500); // Ждём завершения симуляции

        // 4. Получаем результат расшифровки
        mockMvc.perform(get("/api/v1/bot/recordings/{id}/transcription", recordingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transcriptionText").exists())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // Очищаем
        tempWavFile.delete();
    }

    @Test
    @DisplayName("Запуск расшифровки для несуществующей записи")
    void testTranscribeNonExistentRecording() throws Exception {
        mockMvc.perform(post("/api/v1/bot/recordings/{id}/transcribe", "non-existent-id")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Получение расшифровки без предварительного запуска")
    void testGetTranscriptionWithoutStarting() throws Exception {
        // Создаём запись без расшифровки
        RecordingEntity recording = new RecordingEntity();
        recording.setId("test-recording-id");
        recording.setUserId(testUser.getId());
        recording.setFilename("test.wav");
        recording.setFileSize(1024L);
        recordingRepository.save(recording);

        mockMvc.perform(get("/api/v1/bot/recordings/{id}/transcription", recording.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Запуск расшифровки без авторизации")
    void testTranscribeWithoutAuth() throws Exception {
        mockMvc.perform(post("/api/v1/bot/recordings/{id}/transcribe", "some-id"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Проверка статуса расшифровки")
    void testTranscriptionStatus() throws Exception {
        // Создаём запись
        RecordingEntity recording = new RecordingEntity();
        recording.setId("status-test-id");
        recording.setUserId(testUser.getId());
        recording.setFilename("test.wav");
        recording.setFileSize(1024L);
        recordingRepository.save(recording);

        // Запускаем расшифровку
        mockMvc.perform(post("/api/v1/bot/recordings/{id}/transcribe", recording.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
            .andExpect(status().isOk());

        // Сразу после запуска статус должен быть PROCESSING
        TranscriptionEntity transcription = transcriptionRepository.findByRecordingId(recording.getId()).orElseThrow();
        assertEquals(TranscriptionEntity.TranscriptionStatus.PROCESSING, transcription.getStatus());
    }

    // Вспомогательные методы

    private File createTestWavFile() throws Exception {
        // Создаём простой WAV файл с заголовком
        // WAV header для 1 секунды тишины (8kHz, 8-bit, mono)
        byte[] wavHeader = new byte[] {
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x24, 0x00, 0x00, 0x00, // File size - 8
            0x57, 0x41, 0x56, 0x45, // "WAVE"
            0x66, 0x6D, 0x74, 0x20, // "fmt "
            0x10, 0x00, 0x00, 0x00, // Subchunk1Size
            0x01, 0x00,             // AudioFormat (PCM)
            0x01, 0x00,             // NumChannels (mono)
            (byte) 0x80, 0x3E, 0x00, 0x00, // SampleRate (16000)
            (byte) 0x00, 0x7D, 0x00, 0x00, // ByteRate
            0x01, 0x00,             // BlockAlign
            0x08, 0x00,             // BitsPerSample
            0x64, 0x61, 0x74, 0x61, // "data"
            0x00, 0x00, 0x00, 0x00  // Subchunk2Size
        };

        byte[] audioData = new byte[0]; // Пустые данные для теста

        File tempFile = File.createTempFile("test", ".wav");
        try (var fos = new java.io.FileOutputStream(tempFile)) {
            fos.write(wavHeader);
            fos.write(audioData);
        }

        return tempFile;
    }

    private String extractRecordingId(String jsonResponse) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(jsonResponse);
        return node.path("data").path("id").asText();
    }
}
