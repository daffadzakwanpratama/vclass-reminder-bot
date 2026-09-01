package com.bot.reminder.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "courses")
public class Course {

    @Id
    private Long id; // Moodle course ID

    private String fullName;
    private String shortName;
    private LocalDateTime createdAt = LocalDateTime.now();

    public Course() {}

    public Course(Long id, String fullName, String shortName) {
        this.id = id;
        this.fullName = fullName;
        this.shortName = shortName;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
