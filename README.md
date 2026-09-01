# 🤖 VClass Automated Reminder Bot

Sistem pemantau otomatis VClass Gunadarma berbasis **Spring Boot 3 (Java 21)** dan **SQLite** yang mendeteksi tugas, kuis, dan ujian baru, lalu mengirimkan notifikasi langsung ke akun **Telegram** mahasiswa secara terjadwal.

---

## 🌟 Fitur Utama

- ⚡ **Integrasi REST API Resmi Moodle**: Menggunakan protokol Web Services Moodle (`/webservice/rest/server.php`) yang cepat, stabil, dan tidak membebani server kampus.
- ⏰ **Jadwal Otomatis Berkala**: Berjalan otomatis **setiap hari pada jam 12:00 siang dan 18:00 sore WIB** via Spring `@Scheduled` cron.
- 🚀 **Initial Startup Check**: Otomatis mengecek dan mendeteksi aktivitas baru begitu aplikasi dinyalakan.
- 🛡️ **Anti Duplikasi 100%**: Setiap aktivitas dicatat di database SQLite lokal (`reminder.db`) dengan unique constraint `(moodle_id, type)`. Aktivitas yang sudah pernah dicatat dan dinotifikasi tidak akan dikirim ulang.
- 📱 **Format Pesan Rapi**: Dilengkapi dengan nama mata kuliah, deadline dalam format tanggal Indonesia WIB, serta tautan langsung untuk membuka aktivitas di VClass.
- 🔄 **Auto Token Refresh & Retry**: Jika token kedaluwarsa atau terjadi gangguan jaringan, sistem otomatis login ulang dan melakukan retry hingga 3x.

---

## 📁 Struktur Folder

```text
chat-bot-reminder/
├── src/main/java/com/bot/reminder/
│   ├── ReminderBotApplication.java       # Main class & dotenv loader
│   ├── config/
│   │   └── AppConfig.java                # RestClient & ObjectMapper beans
│   ├── dto/                              # Moodle API JSON POJO
│   │   ├── MoodleAssignment.java
│   │   ├── MoodleAssignmentResponse.java
│   │   ├── MoodleCourse.java
│   │   ├── MoodleQuizResponse.java
│   │   ├── MoodleSiteInfoResponse.java
│   │   └── MoodleTokenResponse.java
│   ├── model/                            # JPA Entities (SQLite)
│   │   ├── Activity.java                 # Tugas / Kuis yang terdeteksi
│   │   ├── Course.java                   # Mata Kuliah
│   │   └── NotificationLog.java          # Riwayat notifikasi
│   ├── repository/                       # Spring Data JPA Repositories
│   │   ├── ActivityRepository.java
│   │   ├── CourseRepository.java
│   │   └── NotificationLogRepository.java
│   └── service/                          # Business Logic
│       ├── ActivityDetectorService.java  # Deteksi aktivitas baru & filter duplikat
│       ├── MoodleApiService.java         # Komunikasi REST API VClass
│       ├── SchedulerService.java         # Cron job jam 12:00 & 18:00 WIB
│       └── TelegramService.java          # Kirim notifikasi Markdown ke Telegram
├── src/main/resources/
│   └── application.properties            # Konfigurasi aplikasi & SQLite
├── .env                                  # Kredensial & Secrets (Private)
├── .env.example                          # Template konfigurasi
├── .gitignore
├── mvnw.ps1                              # Maven runner script
├── pom.xml                               # Dependensi Maven
├── README.md
└── run.ps1                               # Script sekali klik untuk menjalankan
```

---

## ⚙️ Konfigurasi (`.env`)

Pastikan file `.env` sudah terisi dengan benar:

```properties
# --- VClass Gunadarma ---
VCLASS_URL=https://v-class.gunadarma.ac.id
VCLASS_USERNAME=your_username_or_email
VCLASS_PASSWORD=your_password
VCLASS_SERVICE=moodle_mobile_app

# --- Telegram Bot ---
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id
```

---

## 🚀 Cara Menjalankan

### Cara 1: Menggunakan PowerShell Script
Cukup jalankan script:
```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

### Cara 2: Menggunakan Maven Wrapper
```powershell
powershell -ExecutionPolicy Bypass -File .\mvnw.ps1 spring-boot:run
```

---

## 🧪 Contoh Tampilan Notifikasi Telegram

```
🔔 TUGAS BARU VCLASS

📚 Mata Kuliah:
Pemodelan & Visualisasi Data ** | LILIS SETYOWATI

📌 Nama Aktivitas:
Tugas Kelompok

⏳ Batas Waktu (Deadline):
Senin, 11 Mei 2026 23:59 WIB

🔗 Buka di VClass

—
Sistem Pengingat Otomatis VClass Gunadarma
```
