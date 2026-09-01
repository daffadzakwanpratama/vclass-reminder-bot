package com.bot.reminder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MoodleTokenResponse {
    private String token;
    private String privatetoken;
    private String error;
    private String errorcode;

    public MoodleTokenResponse() {}

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPrivatetoken() {
        return privatetoken;
    }

    public void setPrivatetoken(String privatetoken) {
        this.privatetoken = privatetoken;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getErrorcode() {
        return errorcode;
    }

    public void setErrorcode(String errorcode) {
        this.errorcode = errorcode;
    }
}
