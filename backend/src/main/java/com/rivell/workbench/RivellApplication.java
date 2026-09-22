package com.rivell.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
@ConfigurationPropertiesScan
public class RivellApplication {

    public static void main(String[] args) {
        SpringApplication.run(RivellApplication.class, args);
    }
}
