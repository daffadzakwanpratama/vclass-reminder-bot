package com.bot.reminder.service;

import com.bot.reminder.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
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

import java.util.Collections;
import java.util.List;

@Service
public class MoodleApiService {

    private static final Logger log = LoggerFactory.getLogger(MoodleApiService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${vclass.url:https://v-class.gunadarma.ac.id}")
    private String vclassUrl;

    @Value("${vclass.username}")
    private String username;

    @Value("${vclass.password}")
    private String password;

    @Value("${vclass.service:moodle_mobile_app}")
    private String serviceName;

    private String cachedToken;
    private Long cachedUserId;

    public MoodleApiService(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Authenticate or return cached token.
     */
    public synchronized String getToken() {
        if (cachedToken != null && !cachedToken.isBlank()) {
            return cachedToken;
        }
        return refreshToken();
    }

    public synchronized String refreshToken() {
        log.info("Requesting new Moodle token from VClass...");
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("username", username);
            body.add("password", password);
            body.add("service", serviceName);

            MoodleTokenResponse response = restClient.post()
                    .uri(vclassUrl + "/login/token.php")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(MoodleTokenResponse.class);

            if (response != null && response.getToken() != null) {
                this.cachedToken = response.getToken();
                log.info("Moodle token successfully obtained.");
                return this.cachedToken;
            } else {
                log.error("Failed to obtain Moodle token: error={}", response != null ? response.getError() : "null response");
                throw new RuntimeException("Gagal login ke VClass: " + (response != null ? response.getError() : "Unknown error"));
            }
        } catch (Exception e) {
            log.error("Error during VClass authentication: {}", e.getMessage(), e);
            throw new RuntimeException("Koneksi VClass gagal: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch user site info (to get userId and fullname).
     */
    public MoodleSiteInfoResponse getSiteInfo() {
        String token = getToken();
        String json = callWsFunction("core_webservice_get_site_info", token, null);
        try {
            MoodleSiteInfoResponse res = objectMapper.readValue(json, MoodleSiteInfoResponse.class);
            if (res != null && res.getUserid() != null) {
                this.cachedUserId = res.getUserid();
            }
            return res;
        } catch (Exception e) {
            log.error("Error parsing site info: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch enrolled courses for the logged-in student.
     */
    public List<MoodleCourse> getEnrolledCourses() {
        if (cachedUserId == null) {
            getSiteInfo();
        }
        if (cachedUserId == null) {
            log.warn("Cannot get user ID to fetch courses.");
            return Collections.emptyList();
        }

        String token = getToken();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("userid", String.valueOf(cachedUserId));

        String json = callWsFunction("core_enrol_get_users_courses", token, params);
        try {
            return objectMapper.readValue(json, new TypeReference<List<MoodleCourse>>() {});
        } catch (Exception e) {
            log.error("Error parsing enrolled courses: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetch assignments for the given list of course IDs.
     */
    public MoodleAssignmentResponse getAssignments(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return new MoodleAssignmentResponse();
        }

        String token = getToken();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < courseIds.size(); i++) {
            params.add("courseids[" + i + "]", String.valueOf(courseIds.get(i)));
        }

        String json = callWsFunction("mod_assign_get_assignments", token, params);
        try {
            return objectMapper.readValue(json, MoodleAssignmentResponse.class);
        } catch (Exception e) {
            log.error("Error parsing assignments: {}", e.getMessage());
            return new MoodleAssignmentResponse();
        }
    }

    /**
     * Check if a specific assignment has been submitted by the student.
     */
    public boolean isAssignmentSubmitted(Long assignId) {
        politeDelay(250);
        try {
            String token = getToken();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("assignid", String.valueOf(assignId));

            String json = callWsFunction("mod_assign_get_submission_status", token, params);
            JsonNode root = objectMapper.readTree(json);
            JsonNode statusNode = root.at("/lastattempt/submission/status");
            if (!statusNode.isMissingNode() && "submitted".equalsIgnoreCase(statusNode.asText())) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to check submission status for assignId {}: {}", assignId, e.getMessage());
        }
        return false;
    }

    /**
     * Fetch quizzes for the given list of course IDs.
     */
    public MoodleQuizResponse getQuizzes(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return new MoodleQuizResponse();
        }

        String token = getToken();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < courseIds.size(); i++) {
            params.add("courseids[" + i + "]", String.valueOf(courseIds.get(i)));
        }

        String json = callWsFunction("mod_quiz_get_quizzes_by_courses", token, params);
        try {
            return objectMapper.readValue(json, MoodleQuizResponse.class);
        } catch (Exception e) {
            log.error("Error parsing quizzes: {}", e.getMessage());
            return new MoodleQuizResponse();
        }
    }

    /**
     * Check if a specific quiz has been completed/finished by the student.
     */
    public boolean isQuizCompleted(Long quizId) {
        politeDelay(250);
        try {
            String token = getToken();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("quizid", String.valueOf(quizId));

            String json = callWsFunction("mod_quiz_get_user_attempts", token, params);
            JsonNode root = objectMapper.readTree(json);
            JsonNode attemptsNode = root.get("attempts");
            if (attemptsNode != null && attemptsNode.isArray()) {
                for (JsonNode attempt : attemptsNode) {
                    JsonNode stateNode = attempt.get("state");
                    if (stateNode != null && "finished".equalsIgnoreCase(stateNode.asText())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check quiz attempt status for quizId {}: {}", quizId, e.getMessage());
        }
        return false;
    }

    /**
     * Generic helper to invoke Moodle REST Web Service functions.
     */
    private String callWsFunction(String wsFunction, String token, MultiValueMap<String, String> extraParams) {
        try {
            return executeWsRequest(wsFunction, token, extraParams);
        } catch (Exception e) {
            log.warn("Web service call {} failed ({}), attempting token refresh...", wsFunction, e.getMessage());
            String newToken = refreshToken();
            return executeWsRequest(wsFunction, newToken, extraParams);
        }
    }

    private String executeWsRequest(String wsFunction, String token, MultiValueMap<String, String> extraParams) {
        String uri = vclassUrl + "/webservice/rest/server.php?wstoken=" + token +
                "&wsfunction=" + wsFunction +
                "&moodlewsrestformat=json";

        if (extraParams != null && !extraParams.isEmpty()) {
            StringBuilder sb = new StringBuilder(uri);
            extraParams.forEach((key, values) -> {
                for (String val : values) {
                    sb.append("&").append(key).append("=").append(val);
                }
            });
            uri = sb.toString();
        }

        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }

    /**
     * Polite delay between API requests to avoid rate-limiting and burst traffic.
     */
    private void politeDelay(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
