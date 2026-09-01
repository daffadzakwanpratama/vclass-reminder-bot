package com.bot.reminder.service;

import com.bot.reminder.dto.*;
import com.bot.reminder.model.Activity;
import com.bot.reminder.model.Course;
import com.bot.reminder.model.NotificationLog;
import com.bot.reminder.repository.ActivityRepository;
import com.bot.reminder.repository.CourseRepository;
import com.bot.reminder.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ActivityDetectorService {

    private static final Logger log = LoggerFactory.getLogger(ActivityDetectorService.class);

    private final MoodleApiService moodleApiService;
    private final ActivityRepository activityRepository;
    private final CourseRepository courseRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final TelegramService telegramService;

    @Value("${vclass.url:https://v-class.gunadarma.ac.id}")
    private String vclassUrl;

    private static final ZoneId ZONE_JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private record ProcessResult(Activity activity, boolean newlyNotified, boolean statusChanged) {}

    public ActivityDetectorService(MoodleApiService moodleApiService,
                                   ActivityRepository activityRepository,
                                   CourseRepository courseRepository,
                                   NotificationLogRepository notificationLogRepository,
                                   TelegramService telegramService) {
        this.moodleApiService = moodleApiService;
        this.activityRepository = activityRepository;
        this.courseRepository = courseRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.telegramService = telegramService;
    }

    /**
     * Scan VClass, detect new activities, and notify pending tasks or status changes.
     * Silent for already-completed tasks to prevent spam.
     */
    @Transactional
    public int checkAndNotifyNewActivities() {
        log.info("Starting VClass check & status evaluation...");

        List<MoodleCourse> enrolledCourses = moodleApiService.getEnrolledCourses();
        if (enrolledCourses.isEmpty()) {
            log.info("No enrolled courses found on VClass (student unenrolled). Purging old semester activities and courses from database...");
            activityRepository.deleteAll();
            courseRepository.deleteAll();
            return 0;
        }

        Map<Long, String> courseNameMap = new HashMap<>();
        for (MoodleCourse mc : enrolledCourses) {
            courseNameMap.put(mc.getId(), mc.getFullname());
            Course course = new Course(mc.getId(), mc.getFullname(), mc.getShortname());
            courseRepository.save(course);
        }

        List<Long> courseIds = new ArrayList<>(courseNameMap.keySet());

        // Auto-clean any old activities and courses that are no longer in enrolled courses
        activityRepository.deleteByCourseIdNotIn(courseIds);
        courseRepository.deleteByIdNotIn(courseIds);

        log.info("Fetched {} active courses. Checking assignments and quizzes...", courseIds.size());

        List<Activity> processedActivities = new ArrayList<>();
        int newlyNotifiedCount = 0;
        int statusChangedCount = 0;

        // 1. Process Assignments
        MoodleAssignmentResponse assignmentResponse = moodleApiService.getAssignments(courseIds);
        if (assignmentResponse != null && assignmentResponse.getCourses() != null) {
            for (MoodleAssignmentResponse.CourseAssignments ca : assignmentResponse.getCourses()) {
                String courseName = courseNameMap.getOrDefault(ca.getId(), ca.getFullname());
                if (ca.getAssignments() != null) {
                    for (MoodleAssignment assign : ca.getAssignments()) {
                        ProcessResult result = processAssignment(assign, ca.getId(), courseName);
                        processedActivities.add(result.activity());
                        if (result.newlyNotified()) newlyNotifiedCount++;
                        if (result.statusChanged()) statusChangedCount++;
                    }
                }
            }
        }

        // 2. Process Quizzes
        MoodleQuizResponse quizResponse = moodleApiService.getQuizzes(courseIds);
        if (quizResponse != null && quizResponse.getQuizzes() != null) {
            for (MoodleQuizResponse.MoodleQuiz quiz : quizResponse.getQuizzes()) {
                String courseName = courseNameMap.getOrDefault(quiz.getCourse(), "Mata Kuliah #" + quiz.getCourse());
                ProcessResult result = processQuiz(quiz, courseName);
                processedActivities.add(result.activity());
                if (result.newlyNotified()) newlyNotifiedCount++;
                if (result.statusChanged()) statusChangedCount++;
            }
        }

        // 3. Check for any urgent deadline reminders (< 2 hours)
        checkAndSendDeadlineAlerts();

        log.info("VClass check complete. Total activities: {}, Newly notified pending: {}, Status changes: {}",
                processedActivities.size(), newlyNotifiedCount, statusChangedCount);

        return newlyNotifiedCount + statusChangedCount;
    }

    /**
     * Check for tasks due within the next 2 hours and send urgent alert if not yet sent.
     */
    @Transactional
    public int checkAndSendDeadlineAlerts() {
        LocalDateTime now = LocalDateTime.now(ZONE_JAKARTA);
        LocalDateTime twoHoursLater = now.plusHours(2);

        List<Activity> candidates = activityRepository.findByIsCompletedFalseAndDueDateIsNotNullAndReminderSentAtIsNull();
        int alertCount = 0;

        for (Activity act : candidates) {
            if (act.getDueDate() != null && act.getDueDate().isAfter(now) && act.getDueDate().isBefore(twoHoursLater)) {
                long minutesLeft = Duration.between(now, act.getDueDate()).toMinutes();
                boolean sent = telegramService.sendDeadlineAlert(act, minutesLeft);
                if (sent) {
                    act.setReminderSentAt(now);
                    activityRepository.save(act);
                    alertCount++;
                    log.info("Urgent H-2 deadline alert sent for: {} ({} mins left)", act.getName(), minutesLeft);
                }
            }
        }
        return alertCount;
    }

    // ----------------------------------------------------------------
    // Process Assignment
    // ----------------------------------------------------------------

    private ProcessResult processAssignment(MoodleAssignment assign, Long courseId, String courseName) {
        Optional<Activity> existingOpt = activityRepository.findByMoodleIdAndType(assign.getId(), "ASSIGNMENT");
        boolean existsInDb = existingOpt.isPresent();
        boolean previouslyCompleted = existsInDb && Boolean.TRUE.equals(existingOpt.get().getIsCompleted());

        boolean submitted;
        if (previouslyCompleted) {
            submitted = true;
            log.debug("Assignment {} already completed in DB. Skipping API call.", assign.getId());
        } else {
            submitted = moodleApiService.isAssignmentSubmitted(assign.getId());
        }

        Activity activity = existingOpt.orElseGet(Activity::new);
        if (!existsInDb) {
            activity.setMoodleId(assign.getId());
            activity.setCourseId(courseId);
            activity.setType("ASSIGNMENT");
            activity.setFirstSeenAt(LocalDateTime.now());
        }

        LocalDateTime dueDateTime = toLocalDateTime(assign.getDuedate());
        LocalDateTime openDateTime = toLocalDateTime(assign.getAllowsubmissionsfromdate());
        String url = vclassUrl + "/mod/assign/view.php?id="
                + (assign.getCmid() != null ? assign.getCmid() : assign.getId());

        activity.setCourseName(courseName);
        activity.setName(assign.getName());
        activity.setDescription(assign.getIntro());
        activity.setDueDate(dueDateTime);
        activity.setOpenDate(openDateTime);
        activity.setUrl(url);
        activity.setIsCompleted(submitted);
        activity.setCompletionStatus(submitted ? "SUDAH DIKERJAKAN" : "BELUM DIKERJAKAN");
        activity = activityRepository.save(activity);

        // Notify Logic: Only notify if newly detected AND BELUM DIKERJAKAN
        if (!existsInDb || activity.getNotifiedAt() == null) {
            if (!submitted) {
                boolean sent = telegramService.sendActivityNotification(activity);
                if (sent) {
                    activity.setNotifiedAt(LocalDateTime.now());
                    activity = activityRepository.save(activity);
                }
                saveNotificationLog(activity, sent, "NEW_ACTIVITY",
                        "Assignment: " + activity.getName() + " | " + activity.getCompletionStatus());
                return new ProcessResult(activity, true, false);
            } else {
                // Already completed when found: Mark as notified silently to prevent spam
                activity.setNotifiedAt(LocalDateTime.now());
                activity = activityRepository.save(activity);
                return new ProcessResult(activity, false, false);
            }

        } else if (!previouslyCompleted && submitted) {
            // Status changed: BELUM -> SUDAH
            boolean sent = telegramService.sendCompletionUpdate(activity);
            saveNotificationLog(activity, sent, "STATUS_CHANGE", "Assignment selesai: " + activity.getName());
            log.info("Assignment status changed to COMPLETED: {}", activity.getName());
            return new ProcessResult(activity, false, true);
        }

        return new ProcessResult(activity, false, false);
    }

    // ----------------------------------------------------------------
    // Process Quiz
    // ----------------------------------------------------------------

    private ProcessResult processQuiz(MoodleQuizResponse.MoodleQuiz quiz, String courseName) {
        Optional<Activity> existingOpt = activityRepository.findByMoodleIdAndType(quiz.getId(), "QUIZ");
        boolean existsInDb = existingOpt.isPresent();
        boolean previouslyCompleted = existsInDb && Boolean.TRUE.equals(existingOpt.get().getIsCompleted());

        boolean finished;
        if (previouslyCompleted) {
            finished = true;
            log.debug("Quiz {} already completed in DB. Skipping API call.", quiz.getId());
        } else {
            finished = moodleApiService.isQuizCompleted(quiz.getId());
        }

        Activity activity = existingOpt.orElseGet(Activity::new);
        if (!existsInDb) {
            activity.setMoodleId(quiz.getId());
            activity.setCourseId(quiz.getCourse());
            activity.setType("QUIZ");
            activity.setFirstSeenAt(LocalDateTime.now());
        }

        LocalDateTime dueDateTime = toLocalDateTime(quiz.getTimeclose());
        LocalDateTime openDateTime = toLocalDateTime(quiz.getTimeopen());
        String url = vclassUrl + "/mod/quiz/view.php?id="
                + (quiz.getCoursemodule() != null ? quiz.getCoursemodule() : quiz.getId());

        activity.setCourseName(courseName);
        activity.setName(quiz.getName());
        activity.setDescription(quiz.getIntro());
        activity.setDueDate(dueDateTime);
        activity.setOpenDate(openDateTime);
        activity.setUrl(url);
        activity.setIsCompleted(finished);
        activity.setCompletionStatus(finished ? "SUDAH DIKERJAKAN" : "BELUM DIKERJAKAN");
        activity = activityRepository.save(activity);

        // Notify Logic: Only notify if newly detected AND BELUM DIKERJAKAN
        if (!existsInDb || activity.getNotifiedAt() == null) {
            if (!finished) {
                boolean sent = telegramService.sendActivityNotification(activity);
                if (sent) {
                    activity.setNotifiedAt(LocalDateTime.now());
                    activity = activityRepository.save(activity);
                }
                saveNotificationLog(activity, sent, "NEW_ACTIVITY",
                        "Quiz: " + activity.getName() + " | " + activity.getCompletionStatus());
                return new ProcessResult(activity, true, false);
            } else {
                // Already completed when found: Mark as notified silently to prevent spam
                activity.setNotifiedAt(LocalDateTime.now());
                activity = activityRepository.save(activity);
                return new ProcessResult(activity, false, false);
            }

        } else if (!previouslyCompleted && finished) {
            // Status changed: BELUM -> SUDAH
            boolean sent = telegramService.sendCompletionUpdate(activity);
            saveNotificationLog(activity, sent, "STATUS_CHANGE", "Quiz selesai: " + activity.getName());
            log.info("Quiz status changed to COMPLETED: {}", activity.getName());
            return new ProcessResult(activity, false, true);
        }

        return new ProcessResult(activity, false, false);
    }

    // ----------------------------------------------------------------
    // Reports for Telegram Commands (/tugas, /selesai, /semua)
    // ----------------------------------------------------------------

    public String getPendingTasksReport() {
        LocalDateTime now = LocalDateTime.now(ZONE_JAKARTA);
        List<Activity> pending = activityRepository.findByIsCompletedFalse();

        List<Activity> activePending = pending.stream()
                .filter(a -> a.getDueDate() == null || a.getDueDate().isAfter(now))
                .sorted(Comparator.comparing(a -> a.getDueDate() != null ? a.getDueDate() : LocalDateTime.MAX))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("📋 DAFTAR TUGAS BELUM DIKERJAKAN\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        if (activePending.isEmpty()) {
            sb.append("🎉 Luar biasa! Tidak ada tugas atau kuis yang tertunda.\n");
            sb.append("Semua tugas kamu sudah selesai dikerjakan! 🏆\n");
        } else {
            int index = 1;
            for (Activity a : activePending) {
                String deadline = a.getDueDate() != null ? a.getDueDate().format(DATE_FMT) : "Tanpa Deadline";
                String courseShort = a.getCourseName() != null ? cleanCourseName(a.getCourseName()) : "VClass";
                String typeIcon = "QUIZ".equalsIgnoreCase(a.getType()) ? "📝" : "📋";

                sb.append(index++).append(". ").append(typeIcon).append(" ")
                  .append(cleanHtml(a.getName())).append("\n");
                sb.append("   📖 ").append(cleanHtml(courseShort)).append("\n");
                sb.append("   ⏳ Deadline: ").append(deadline).append(" WIB\n");
                if (a.getUrl() != null && !a.getUrl().isBlank()) {
                    sb.append("   🔗 Link: ").append(a.getUrl()).append("\n");
                }
                sb.append("\n");
            }
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total pending aktif: ").append(activePending.size());
        return sb.toString();
    }

    public String getCompletedTasksReport() {
        List<Activity> completed = activityRepository.findByIsCompletedTrue();

        StringBuilder sb = new StringBuilder();
        sb.append("✅ DAFTAR TUGAS SUDAH DIKERJAKAN\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        if (completed.isEmpty()) {
            sb.append("Belum ada tugas yang tercatat selesai di sistem.\n");
        } else {
            // Group by course name
            Map<String, List<Activity>> byCourse = new TreeMap<>();
            for (Activity a : completed) {
                String cName = cleanCourseName(a.getCourseName() != null ? a.getCourseName() : "Lainnya");
                byCourse.computeIfAbsent(cName, k -> new ArrayList<>()).add(a);
            }

            byCourse.forEach((course, acts) -> {
                sb.append("📚 ").append(cleanHtml(course)).append(" (").append(acts.size()).append(" selesai)\n");
                for (Activity a : acts) {
                    sb.append("   ✔ ").append(cleanHtml(a.getName())).append("\n");
                }
                sb.append("\n");
            });
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total tugas selesai: ").append(completed.size());
        return sb.toString();
    }

    public String getAllTasksReport() {
        List<Activity> all = activityRepository.findAllByOrderByCourseNameAscDueDateAsc();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 SEMUA AKTIVITAS VCLASS & STATUS\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        if (all.isEmpty()) {
            sb.append("Belum ada data aktivitas di sistem. Coba ketik /cek untuk sinkronisasi.\n");
        } else {
            Map<String, List<Activity>> byCourse = new TreeMap<>();
            for (Activity a : all) {
                String cName = cleanCourseName(a.getCourseName() != null ? a.getCourseName() : "Lainnya");
                byCourse.computeIfAbsent(cName, k -> new ArrayList<>()).add(a);
            }

            byCourse.forEach((course, acts) -> {
                sb.append("📚 ").append(cleanHtml(course)).append("\n");
                for (Activity a : acts) {
                    String statusBadge = Boolean.TRUE.equals(a.getIsCompleted()) ? "✅" : "⏳";
                    String deadline = a.getDueDate() != null ? " (" + a.getDueDate().format(DATE_FMT) + ")" : "";
                    sb.append("   ").append(statusBadge).append(" ").append(cleanHtml(a.getName()))
                      .append(deadline).append("\n");
                }
                sb.append("\n");
            });
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        long completed = all.stream().filter(a -> Boolean.TRUE.equals(a.getIsCompleted())).count();
        sb.append("✅ Selesai: ").append(completed).append(" | ⏳ Pending: ").append(all.size() - completed);
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Utility helpers
    // ----------------------------------------------------------------

    private LocalDateTime toLocalDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZONE_JAKARTA);
    }

    private void saveNotificationLog(Activity activity, boolean sent, String eventType, String description) {
        NotificationLog nLog = new NotificationLog(
                activity.getId(), "TELEGRAM",
                sent ? "SUCCESS" : "FAILED",
                "[" + eventType + "] " + description
        );
        notificationLogRepository.save(nLog);
    }

    private String cleanCourseName(String name) {
        String clean = telegramService.cleanHtml(name);
        String[] parts = clean.split("\\|");
        if (parts.length >= 3) {
            return parts[1].trim() + " - " + parts[2].trim();
        }
        return clean;
    }

    private String cleanHtml(String input) {
        return telegramService.cleanHtml(input);
    }

    /**
     * Manually reset all stored activities and courses (e.g. for a new semester).
     */
    @Transactional
    public void resetAllData() {
        activityRepository.deleteAll();
        courseRepository.deleteAll();
        notificationLogRepository.deleteAll();
        log.info("Manual reset executed: All local activity and course data wiped clean.");
    }
}
