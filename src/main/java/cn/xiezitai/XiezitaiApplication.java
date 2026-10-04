package cn.xiezitai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XiezitaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(XiezitaiApplication.class, args);
    }
}
