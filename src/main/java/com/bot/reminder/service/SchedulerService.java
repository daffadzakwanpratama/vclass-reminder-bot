package com.bot.reminder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ActivityDetectorService activityDetectorService;
    private final TelegramService telegramService;
    private final AtomicBoolean autoEnabled = new AtomicBoolean(true);

    public SchedulerService(ActivityDetectorService activityDetectorService, TelegramService telegramService) {
        this.activityDetectorService = activityDetectorService;
        this.telegramService = telegramService;
    }

    public boolean isAutoEnabled() {
        return autoEnabled.get();
    }

    public void setAutoEnabled(boolean enabled) {
        this.autoEnabled.set(enabled);
    }

    /**
     * Run initial check automatically when application starts up.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Application started. Executing initial VClass check...");
        try {
            int changes = activityDetectorService.checkAndNotifyNewActivities();
            log.info("Initial startup check completed. {} changes detected.", changes);
            telegramService.sendMessage("""
                    ✅ VClass Reminder Bot Siap!
                    ━━━━━━━━━━━━━━━━━━━━━━
                    🤖 Status Fitur Otomatis: AKTIF
                    - Pengecekan tugas baru tiap 1 jam
                    - Pengingat otomatis H-2 jam sebelum deadline
                    - Perintah chat aktif: ketik /help atau /tugas
                    ━━━━━━━━━━━━━━━━━━━━━━
                    💡 Tips: Ketik /matikan jika ingin mematikan scan otomatis.""");
        } catch (Exception e) {
            log.error("Error during startup VClass check: {}", e.getMessage(), e);
        }
    }

    /**
     * Hourly VClass check for newly posted assignments and status changes.
     * Runs at the start of every hour (e.g. 01:00, 02:00, ..., 14:00, etc.)
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Jakarta")
    public void runHourlyVClassCheck() {
        if (!autoEnabled.get()) {
            log.debug("Hourly VClass check skipped (Auto mode is OFF).");
            return;
        }

        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("Hourly scheduled VClass check triggered at {}", currentTime);

        try {
            int changes = activityDetectorService.checkAndNotifyNewActivities();
            log.info("Hourly check finished. {} changes processed.", changes);
        } catch (Exception e) {
            log.warn("Hourly VClass check encountered an error: {}", e.getMessage());
        }
    }

    /**
     * Urgent Deadline Check (H-2 hours alert).
     * Runs every 15 minutes to ensure no deadline is missed.
     */
    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Jakarta")
    public void runDeadlineAlertCheck() {
        if (!autoEnabled.get()) {
            log.debug("Deadline alert check skipped (Auto mode is OFF).");
            return;
        }

        try {
            int alertsSent = activityDetectorService.checkAndSendDeadlineAlerts();
            if (alertsSent > 0) {
                log.info("Sent {} urgent deadline alerts.", alertsSent);
            }
        } catch (Exception e) {
            log.warn("Deadline alert check encountered an error: {}", e.getMessage());
        }
    }
}
