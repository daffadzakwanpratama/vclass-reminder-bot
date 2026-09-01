package com.bot.reminder.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramService telegramService;
    private final ActivityDetectorService activityDetectorService;
    private final SchedulerService schedulerService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;
    private long lastUpdateId = 0;

    @org.springframework.beans.factory.annotation.Value("${telegram.chat-id}")
    private String authorizedChatId;

    private final java.util.Map<String, java.util.List<Long>> commandTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_WINDOW = 5;
    private static final long WINDOW_MILLIS = 10_000L; // 10 seconds

    public TelegramPollingService(TelegramService telegramService,
                                  ActivityDetectorService activityDetectorService,
                                  SchedulerService schedulerService) {
        this.telegramService = telegramService;
        this.activityDetectorService = activityDetectorService;
        this.schedulerService = schedulerService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startPolling() {
        telegramService.registerBotCommands();
        executor.submit(this::pollLoop);
        log.info("Telegram Command Polling Service started with STRICT Security Guard.");
    }

    private void pollLoop() {
        while (running) {
            try {
                JsonNode root = telegramService.getUpdates(lastUpdateId + 1, 10);
                if (root != null && root.path("ok").asBoolean(false)) {
                    JsonNode results = root.path("result");
                    if (results.isArray()) {
                        for (JsonNode update : results) {
                            long updateId = update.path("update_id").asLong();
                            if (updateId > lastUpdateId) {
                                lastUpdateId = updateId;
                            }
                            handleUpdate(update);
                        }
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Telegram polling cycle error: {}", e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private boolean isRateLimited(String chatId) {
        long now = System.currentTimeMillis();
        java.util.List<Long> timestamps = commandTimestamps.computeIfAbsent(chatId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        timestamps.removeIf(t -> now - t > WINDOW_MILLIS);
        if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
            return true;
        }
        timestamps.add(now);
        return false;
    }

    private boolean isAuthorized(String chatId) {
        if (authorizedChatId == null || authorizedChatId.isBlank()) {
            return false;
        }
        return authorizedChatId.trim().equals(chatId.trim());
    }

    private void handleUpdate(JsonNode update) {
        try {
            JsonNode message = update.path("message");
            if (message.isMissingNode()) return;

            String text = message.path("text").asText("").trim();
            String chatId = message.path("chat").path("id").asText();

            if (text.isBlank() || chatId.isBlank()) return;

            // --- 🛡️ SECURITY GUARD 1: Strict Chat ID Authorization Whitelist ---
            if (!isAuthorized(chatId)) {
                log.warn("🚨 [SECURITY ALERT] Unauthorized access attempt from Telegram Chat ID: {} | Message: {}", chatId, text);
                telegramService.sendMessageToChat(chatId, "⛔ AKSES DITOLAK\n━━━━━━━━━━━━━━━━━━━━━━\nAkun Telegram Anda tidak terdaftar sebagai pemilik bot ini.");
                return;
            }

            // --- 🛡️ SECURITY GUARD 2: Rate Limiting & Anti-Spam ---
            if (isRateLimited(chatId)) {
                log.warn("⚠️ Rate limit triggered for Chat ID: {}", chatId);
                telegramService.sendMessageToChat(chatId, "⏳ Terlalu banyak permintaan! Harap tunggu beberapa detik.");
                return;
            }

            log.info("Received authorized Telegram command from {}: {}", chatId, text);

            String[] parts = text.split("\\s+");
            String command = parts[0].toLowerCase();
            if (command.contains("@")) {
                command = command.substring(0, command.indexOf("@"));
            }

            // Normalize keyboard button clicks
            String cleanText = text.toLowerCase();
            if (cleanText.contains("tugas belum") || cleanText.equals("daftar tugas") || cleanText.equals("tugas")) {
                command = "/tugas";
            } else if (cleanText.contains("tugas selesai") || cleanText.equals("selesai")) {
                command = "/selesai";
            } else if (cleanText.contains("cek vclass") || cleanText.equals("cek")) {
                command = "/cek";
            } else if (cleanText.contains("status bot") || cleanText.equals("status")) {
                command = "/status";
            } else if (cleanText.contains("panduan") || cleanText.contains("menu") || cleanText.equals("help") || cleanText.equals("bantuan")) {
                command = "/help";
            } else if (cleanText.contains("atur lokasi shalat") || cleanText.contains("jadwal hari ini")) {
                telegramService.sendMessageToChat(chatId, "ℹ️ Tombol lama telah diperbarui ke menu VClass Reminder Bot.");
                sendHelpMenu(chatId);
                return;
            }

            switch (command) {
                case "/start", "/help" -> sendHelpMenu(chatId);
                case "/tugas", "/deadline", "/pending" -> {
                    String report = activityDetectorService.getPendingTasksReport();
                    telegramService.sendMessageToChat(chatId, report);
                }
                case "/selesai", "/done" -> {
                    String report = activityDetectorService.getCompletedTasksReport();
                    telegramService.sendMessageToChat(chatId, report);
                }
                case "/semua", "/all" -> {
                    String report = activityDetectorService.getAllTasksReport();
                    telegramService.sendMessageToChat(chatId, report);
                }
                case "/cek", "/sync" -> {
                    telegramService.sendMessageToChat(chatId, "🔄 Sedang memeriksa VClass Gunadarma...");
                    try {
                        int changes = activityDetectorService.checkAndNotifyNewActivities();
                        telegramService.sendMessageToChat(chatId, "✅ Sinkronisasi VClass selesai!\n" +
                                (changes > 0 ? "Ditemukan " + changes + " update aktivitas." : "Tidak ada tugas baru atau perubahan status."));
                    } catch (Exception e) {
                        telegramService.sendMessageToChat(chatId, "❌ Gagal memeriksa VClass: " + e.getMessage());
                    }
                }
                case "/reset", "/bersihkan" -> {
                    try {
                        activityDetectorService.resetAllData();
                        telegramService.sendMessageToChat(chatId, """
                                🗑️ Database Berhasil Dibersihkan!
                                ━━━━━━━━━━━━━━━━━━━━━━
                                Semua data tugas dan mata kuliah semester lalu telah dihapus dari sistem.
                                Database sekarang 100% bersih dan siap mencatat mata kuliah baru semester depan. 🚀""");
                    } catch (Exception e) {
                        telegramService.sendMessageToChat(chatId, "❌ Gagal me-reset database: " + e.getMessage());
                    }
                }
                case "/matikan", "/stop" -> {
                    schedulerService.setAutoEnabled(false);
                    telegramService.sendMessageToChat(chatId, """
                            ⏸️ Fitur Pengecekan Otomatis Telah DIMATIKAN.
                            ━━━━━━━━━━━━━━━━━━━━━━
                            Bot tidak akan melakukan pengecekan VClass atau pengingat deadline secara otomatis.

                            💡 Kamu tetap bisa menggunakan perintah manual seperti /cek, /tugas, /selesai kapan saja.
                            Ketik /aktifkan untuk menyalakan kembali.""");
                }
                case "/aktifkan", "/on" -> {
                    schedulerService.setAutoEnabled(true);
                    telegramService.sendMessageToChat(chatId, """
                            ▶️ Fitur Pengecekan Otomatis Telah DIAKTIFKAN.
                            ━━━━━━━━━━━━━━━━━━━━━━
                            Bot akan kembali:
                            - Memeriksa tugas baru tiap 1 jam
                            - Mengingatkan otomatis H-2 jam sebelum deadline""");
                }
                case "/auto" -> {
                    if (parts.length > 1 && parts[1].equalsIgnoreCase("off")) {
                        schedulerService.setAutoEnabled(false);
                        telegramService.sendMessageToChat(chatId, "⏸️ Pengecekan otomatis: NONAKTIF");
                    } else if (parts.length > 1 && parts[1].equalsIgnoreCase("on")) {
                        schedulerService.setAutoEnabled(true);
                        telegramService.sendMessageToChat(chatId, "▶️ Pengecekan otomatis: AKTIF");
                    } else {
                        boolean isAuto = schedulerService.isAutoEnabled();
                        telegramService.sendMessageToChat(chatId, "Status Pengecekan Otomatis: " + (isAuto ? "✅ AKTIF" : "⏸️ NONAKTIF") +
                                "\nKetik `/auto on` atau `/auto off` untuk mengubah.");
                    }
                }
                case "/status" -> {
                    boolean isAuto = schedulerService.isAutoEnabled();
                    telegramService.sendMessageToChat(chatId, "📊 STATUS BOT VCLASS\n━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "🤖 Pengecekan Otomatis : " + (isAuto ? "✅ AKTIF" : "⏸️ NONAKTIF (Manual Only)") + "\n" +
                            "🕒 Interval Scan      : Tiap 1 Jam\n" +
                            "🚨 Alert Deadline      : H-2 Jam\n\n" +
                            "Gunakan tombol di bawah atau /tugas untuk cek tanggungan.");
                }
                default -> {
                    if (text.startsWith("/")) {
                        telegramService.sendMessageToChat(chatId,
                                "Perintah tidak dikenali.\nKetik /help atau pilih menu di keyboard bawah.");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing Telegram update: {}", e.getMessage(), e);
        }
    }

    private void sendHelpMenu(String chatId) {
        boolean isAuto = schedulerService.isAutoEnabled();
        String autoStatus = isAuto ? "✅ AKTIF" : "⏸️ NONAKTIF";

        String msg = """
                🤖 MENU BOT REMINDER VCLASS
                ━━━━━━━━━━━━━━━━━━━━━━
                Status Otomatis: """ + autoStatus + """

                
                📋 Perintah Tugas:
                • /tugas   - Lihat tugas BELUM dikerjakan
                • /selesai - Lihat tugas SUDAH dikerjakan
                • /semua   - Lihat semua tugas & status per matkul
                • /cek     - Paksa cek VClass sekarang (manual)
                • /reset   - Bersihkan data lama semester lalu

                ⚙️ Kontrol Otomatis:
                • /matikan - Matikan scan & pengingat otomatis
                • /aktifkan - Nyalakan scan & pengingat otomatis
                • /status  - Cek status sistem bot

                ━━━━━━━━━━━━━━━━━━━━━━
                Sistem Pengingat Otomatis VClass Gunadarma""";
        telegramService.sendMessageToChat(chatId, msg);
    }
}
