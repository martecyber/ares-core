package com.martecyber.ares.jobs.dto;

public record UpdateJobRequest(String status, Integer progress, String result, String error) {}
