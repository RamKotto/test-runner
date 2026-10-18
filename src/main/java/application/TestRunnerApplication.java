package application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TestRunnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestRunnerApplication.class, args);
    }
}
