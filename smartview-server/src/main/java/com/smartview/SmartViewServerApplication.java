package com.smartview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartViewServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartViewServerApplication.class, args);
    }
}
