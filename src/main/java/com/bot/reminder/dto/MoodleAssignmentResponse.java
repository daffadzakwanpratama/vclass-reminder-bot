package com.bot.reminder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MoodleAssignmentResponse {

    private List<CourseAssignments> courses = new ArrayList<>();

    public MoodleAssignmentResponse() {}

    public List<CourseAssignments> getCourses() {
        return courses;
    }

    public void setCourses(List<CourseAssignments> courses) {
        this.courses = courses;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CourseAssignments {
        private Long id;
        private String fullname;
        private String shortname;
        private List<MoodleAssignment> assignments = new ArrayList<>();

        public CourseAssignments() {}

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getFullname() {
            return fullname;
        }

        public void setFullname(String fullname) {
            this.fullname = fullname;
        }

        public String getShortname() {
            return shortname;
        }

        public void setShortname(String shortname) {
            this.shortname = shortname;
        }

        public List<MoodleAssignment> getAssignments() {
            return assignments;
        }

        public void setAssignments(List<MoodleAssignment> assignments) {
            this.assignments = assignments;
        }
    }
}
