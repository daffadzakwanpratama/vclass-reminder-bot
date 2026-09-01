package com.bot.reminder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MoodleQuizResponse {

    private List<MoodleQuiz> quizzes = new ArrayList<>();

    public MoodleQuizResponse() {}

    public List<MoodleQuiz> getQuizzes() {
        return quizzes;
    }

    public void setQuizzes(List<MoodleQuiz> quizzes) {
        this.quizzes = quizzes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MoodleQuiz {
        private Long id;
        private Long course;
        private Long coursemodule;
        private String name;
        private String intro;
        private Long timeopen;
        private Long timeclose;
        private Long timelimit;

        public MoodleQuiz() {}

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getCourse() {
            return course;
        }

        public void setCourse(Long course) {
            this.course = course;
        }

        public Long getCoursemodule() {
            return coursemodule;
        }

        public void setCoursemodule(Long coursemodule) {
            this.coursemodule = coursemodule;
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

        public Long getTimeopen() {
            return timeopen;
        }

        public void setTimeopen(Long timeopen) {
            this.timeopen = timeopen;
        }

        public Long getTimeclose() {
            return timeclose;
        }

        public void setTimeclose(Long timeclose) {
            this.timeclose = timeclose;
        }

        public Long getTimelimit() {
            return timelimit;
        }

        public void setTimelimit(Long timelimit) {
            this.timelimit = timelimit;
        }
    }
}
