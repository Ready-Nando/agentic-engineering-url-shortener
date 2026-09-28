package com.example.shortener.abuse;

/**
 * Why a link was reported. Every reason counts the same towards an automatic takedown; it is kept for whoever
 * reviews takedowns afterwards.
 */
public enum AbuseReason {
    SPAM,
    PHISHING,
    MALWARE,
    OTHER
}
