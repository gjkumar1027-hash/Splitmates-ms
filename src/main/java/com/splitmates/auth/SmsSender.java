package com.splitmates.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SMS gateway abstraction. The default implementation only logs; plug in a real
 * provider (MSG91, Twilio, AWS SNS...) by replacing this bean.
 */
@Component
public class SmsSender {
    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

    public void send(String phone, String message) {
        log.info("[SMS to {}] {}", phone, message);
    }
}
