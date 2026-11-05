package application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа сервиса test-runner.
 * <p>
 * Запускает Spring Boot-контекст пакета {@code application}: компоненты (контроллеры, сервисы,
 * репозиторий) находятся сканированием, а классы настроек регистрируются через
 * {@link ConfigurationPropertiesScan}. HTTP-порт и остальные параметры задаются в
 * {@code application.yml}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TestRunnerApplication {

    /**
     * Запускает приложение.
     *
     * @param args аргументы командной строки, передаются в {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(TestRunnerApplication.class, args);
    }
}
