package groom.backend;

import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        exclude = {
                S3AutoConfiguration.class,
        }
)
@EnableScheduling
@EnableKafka
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
