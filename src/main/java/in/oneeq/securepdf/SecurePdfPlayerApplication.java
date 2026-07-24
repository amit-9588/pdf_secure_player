package in.oneeq.securepdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class SecurePdfPlayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurePdfPlayerApplication.class, args);
    }
}
