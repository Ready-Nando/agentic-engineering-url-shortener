package com.example.shortener.analytics;

public class InvalidStatsWindowException extends RuntimeException {

    public InvalidStatsWindowException(int days, int maxDays) {
        super("days must be between 1 and " + maxDays + ", was " + days);
    }
}
