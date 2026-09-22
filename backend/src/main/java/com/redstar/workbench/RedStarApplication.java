package com.redstar.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
public class RedStarApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedStarApplication.class, args);
    }
}
