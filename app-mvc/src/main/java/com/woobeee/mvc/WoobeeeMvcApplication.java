package com.woobeee.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.woobeee.mvc", "com.woobeee.core"})
@ConfigurationPropertiesScan(basePackages = {"com.woobeee.mvc", "com.woobeee.core"})
public class WoobeeeMvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(WoobeeeMvcApplication.class, args);
    }
}
