package com.bot.reminder.repository;

import com.bot.reminder.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {

    boolean existsByMoodleIdAndType(Long moodleId, String type);

    Optional<Activity> findByMoodleIdAndType(Long moodleId, String type);

    List<Activity> findByNotifiedAtIsNull();

    List<Activity> findByIsCompletedFalseAndDueDateIsNotNullAndReminderSentAtIsNull();

    List<Activity> findByIsCompletedFalse();

    List<Activity> findByIsCompletedTrue();

    List<Activity> findAllByOrderByCourseNameAscDueDateAsc();

    void deleteByCourseIdNotIn(Collection<Long> courseIds);
}
