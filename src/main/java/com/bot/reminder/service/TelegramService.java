package com.bot.reminder.service;

import com.bot.reminder.model.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final int MAX_MSG_LEN = 4096;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${telegram.bot-token}")
    private String botToken;

    @Value("${telegram.chat-id}")
    private String chatId;

    @Value("${vclass.url:https://v-class.gunadarma.ac.id}")
    private String vclassUrl;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm", Locale.of("id", "ID"));

    private static final String DEFAULT_REPLY_KEYBOARD = """
            {
              "keyboard": [
                [{"text": "📋 Tugas Belum Selesai"}, {"text": "✅ Tugas Selesai"}],
                [{"text": "🔄 Cek VClass"}, {"text": "📊 Status Bot"}],
                [{"text": "ℹ️ Panduan Bot"}]
              ],
              "resize_keyboard": true,
              "is_persistent": true
            }
            """;

    public TelegramService(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public String getDefaultChatId() {
        return chatId;
    }

    /**
     * Register official Telegram slash commands (/tugas, /selesai, /cek, /status, etc.)
     */
    public void registerBotCommands() {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/setMyCommands";
            String commandsJson = """
                    {
                      "commands": [
                        {"command": "tugas", "description": "Lihat tugas belum dikerjakan"},
                        {"command": "selesai", "description": "Lihat tugas sudah dikerjakan"},
                        {"command": "semua", "description": "Lihat semua tugas & status per matkul"},
                        {"command": "cek", "description": "Paksa cek VClass sekarang"},
                        {"command": "status", "description": "Cek status otomatisasi bot"},
                        {"command": "aktifkan", "description": "Nyalakan scan & alarm otomatis"},
                        {"command": "matikan", "description": "Matikan scan & alarm otomatis"},
                        {"command": "reset", "description": "Bersihkan data semester lalu"},
                        {"command": "help", "description": "Panduan & menu lengkap"}
                      ]
                    }
                    """;

            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(commandsJson)
                    .retrieve()
                    .body(String.class);

            log.info("Telegram bot slash commands registered successfully.");
        } catch (Exception e) {
            log.warn("Failed to register Telegram bot commands: {}", e.getMessage());
        }
    }

    /**
     * Send a raw message to the default chat ID.
     */
    public boolean sendMessage(String text) {
        return sendMessageToChat(this.chatId, text);
    }

    /**
     * Send a raw message to a specific chat ID (supports splitting long messages).
     */
    public boolean sendMessageToChat(String targetChatId, String text) {
        if (botToken == null || botToken.isBlank() || targetChatId == null || targetChatId.isBlank()) {
            log.warn("Telegram bot token or target chat ID is missing.");
            return false;
        }
        if (text == null || text.isBlank()) return false;

        if (text.length() <= MAX_MSG_LEN) {
            return doSendMessage(targetChatId, text);
        }

        log.warn("Message exceeds {} chars ({}). Splitting into parts.", MAX_MSG_LEN, text.length());
        boolean allSent = true;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_MSG_LEN, text.length());
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start) end = lastNewline + 1;
            }
            if (!doSendMessage(targetChatId, text.substring(start, end))) allSent = false;
            start = end;
        }
        return allSent;
    }

    /**
     * Send a formatted notification for a new activity.
     */
    public boolean sendActivityNotification(Activity activity) {
        boolean isDone = Boolean.TRUE.equals(activity.getIsCompleted());
        String icon = "ASSIGNMENT".equalsIgnoreCase(activity.getType()) ? "🔔" : "📝";
        String typeLabel = "ASSIGNMENT".equalsIgnoreCase(activity.getType()) ? "TUGAS" : "KUIS/UJIAN";
        String statusLine = isDone ? "✅ Status: SUDAH DIKERJAKAN" : "⏳ Status: BELUM DIKERJAKAN";

        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(" INFORMASI ").append(typeLabel).append(" VCLASS\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append("📚 Mata Kuliah:\n").append(cleanHtml(activity.getCourseName())).append("\n\n");
        sb.append("📌 Nama Aktivitas:\n").append(cleanHtml(activity.getName())).append("\n\n");
        sb.append(statusLine).append("\n\n");

        if (activity.getOpenDate() != null) {
            sb.append("🕒 Mulai: ").append(activity.getOpenDate().format(DATE_FORMATTER)).append(" WIB\n");
        }

        if (activity.getDueDate() != null) {
            sb.append("⏳ Deadline:\n")
              .append(activity.getDueDate().format(DATE_FORMATTER)).append(" WIB\n\n");
        } else {
            sb.append("⏳ Deadline: Tidak ada\n\n");
        }

        if (activity.getUrl() != null && !activity.getUrl().isBlank()) {
            sb.append("🔗 Link: ").append(activity.getUrl()).append("\n\n");
        } else {
            sb.append("🔗 Link: ").append(vclassUrl).append("\n\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Sistem Pengingat Otomatis VClass Gunadarma");

        return sendMessage(sb.toString());
    }

    /**
     * Send emergency deadline alert when a task is due in less than 2 hours.
     */
    public boolean sendDeadlineAlert(Activity activity, long minutesRemaining) {
        String typeLabel = "ASSIGNMENT".equalsIgnoreCase(activity.getType()) ? "TUGAS" : "KUIS/UJIAN";
        long hours = minutesRemaining / 60;
        long mins = minutesRemaining % 60;
        String timeLeftStr = (hours > 0 ? hours + " jam " : "") + mins + " menit lagi!";

        StringBuilder sb = new StringBuilder();
        sb.append("🚨 PERINGATAN DEADLINE ").append(typeLabel).append(" (< 2 JAM)!\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("📚 Mata Kuliah:\n").append(cleanHtml(activity.getCourseName())).append("\n\n");
        sb.append("📌 Nama Aktivitas:\n").append(cleanHtml(activity.getName())).append("\n\n");
        sb.append("⏳ Batas Waktu:\n")
          .append(activity.getDueDate().format(DATE_FORMATTER)).append(" WIB\n");
        sb.append("⚠️ Sisa Waktu: ").append(timeLeftStr).append("\n\n");

        if (activity.getUrl() != null && !activity.getUrl().isBlank()) {
            sb.append("🔗 Segera Buka & Kerjakan:\n").append(activity.getUrl()).append("\n\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Jangan sampai terlewat! 💪");

        return sendMessage(sb.toString());
    }

    /**
     * Notify when a task status changes from BELUM → SUDAH DIKERJAKAN.
     */
    public boolean sendCompletionUpdate(Activity activity) {
        String typeLabel = "ASSIGNMENT".equalsIgnoreCase(activity.getType()) ? "TUGAS" : "KUIS/UJIAN";

        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(typeLabel).append(" BERHASIL DIKERJAKAN!\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("📚 Mata Kuliah:\n").append(cleanHtml(activity.getCourseName())).append("\n\n");
        sb.append("📌 Nama: ").append(cleanHtml(activity.getName())).append("\n\n");
        sb.append("🎉 Sistem mendeteksi kamu sudah menyelesaikan aktivitas ini.\n");
        sb.append("Tidak perlu khawatir lagi! 💪\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Sistem Pengingat Otomatis VClass Gunadarma");

        return sendMessage(sb.toString());
    }

    /**
     * Long poll Telegram updates.
     */
    public JsonNode getUpdates(long offset, int timeoutSeconds) {
        if (botToken == null || botToken.isBlank()) return null;
        try {
            String url = "https://api.telegram.org/bot" + botToken +
                    "/getUpdates?offset=" + offset +
                    "&timeout=" + timeoutSeconds +
                    "&allowed_updates=[\"message\"]";

            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response != null) {
                return objectMapper.readTree(response);
            }
        } catch (Exception e) {
            log.debug("Error during Telegram getUpdates polling: {}", e.getMessage());
        }
        return null;
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private boolean doSendMessage(String targetChatId, String text) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("chat_id", targetChatId);
            body.add("text", text);
            body.add("reply_markup", DEFAULT_REPLY_KEYBOARD);

            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.debug("Telegram sendMessage response: {}", response);
            return true;
        } catch (Exception e) {
            log.error("Failed to send Telegram message to {}: {}", targetChatId, e.getMessage());
            return false;
        }
    }

    public String cleanHtml(String input) {
        if (input == null) return "";
        return input
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("<[^>]*>", "")
                .trim();
    }
}
