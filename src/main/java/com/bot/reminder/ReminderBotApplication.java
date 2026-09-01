package com.bot.reminder;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReminderBotApplication {

    public static void main(String[] args) {
        // Load .env file and inject all variables into System properties
        // so Spring's ${VAR} placeholders can resolve them
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()  // won't fail if .env doesn't exist (uses OS env vars instead)
                .load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(ReminderBotApplication.class, args);
    }
}
