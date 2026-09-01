package com.bot.reminder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MoodleAssignment {
    private Long id;
    private Long cmid;
    private Long course;
    private String name;
    private String intro;
    private Long duedate;
    private Long cutoffdate;
    private Long allowsubmissionsfromdate;

    public MoodleAssignment() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCmid() {
        return cmid;
    }

    public void setCmid(Long cmid) {
        this.cmid = cmid;
    }

    public Long getCourse() {
        return course;
    }

    public void setCourse(Long course) {
        this.course = course;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public Long getDuedate() {
        return duedate;
    }

    public void setDuedate(Long duedate) {
        this.duedate = duedate;
    }

    public Long getCutoffdate() {
        return cutoffdate;
    }

    public void setCutoffdate(Long cutoffdate) {
        this.cutoffdate = cutoffdate;
    }

    public Long getAllowsubmissionsfromdate() {
        return allowsubmissionsfromdate;
    }

    public void setAllowsubmissionsfromdate(Long allowsubmissionsfromdate) {
        this.allowsubmissionsfromdate = allowsubmissionsfromdate;
    }
}
