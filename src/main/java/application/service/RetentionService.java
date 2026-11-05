package application.service;

import application.repository.ArtifactRepository;
import application.repository.ClarificationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Очистка записей с истёкшим сроком хранения: артефакты этого сервиса и сессии прогонов ({@code service = 'run'}).
 * <p>
 * Запускается при старте приложения и затем каждые 6 часов. Ошибка БД логируется и не останавливает
 * планировщик: следующий запуск повторит очистку.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    /**
     * Интервал очистки: 6 часов в миллисекундах.
     */
    public static final long CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private final ArtifactRepository artifacts;
    private final ClarificationSessionRepository sessions;

    /**
     * Выполняет очистку при старте приложения, когда контекст готов и БД доступна.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanup();
    }

    /**
     * Периодическая очистка: первый запуск через 6 часов после старта, далее каждые 6 часов.
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, initialDelay = CLEANUP_INTERVAL_MS)
    public void cleanupScheduled() {
        cleanup();
    }

    /**
     * Удаляет истёкшие артефакты {@code test-runner} и истёкшие сессии {@code run}.
     */
    public void cleanup() {
        try {
            int files = artifacts.deleteExpired();
            int sessionRows = sessions.deleteExpired();
            log.info("Очистка по сроку: артефактов удалено {}, сессий прогонов удалено {}", files, sessionRows);
        } catch (DataAccessException e) {
            log.warn("Очистка по сроку не выполнена: {}", e.getMessage());
        }
    }
}
