package com.bot.reminder.repository;

import com.bot.reminder.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    void deleteByIdNotIn(Collection<Long> ids);
}
