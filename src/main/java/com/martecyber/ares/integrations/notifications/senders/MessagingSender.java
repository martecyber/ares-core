package com.martecyber.ares.integrations.notifications.senders;

import java.util.Map;

import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;

/**
 * Transport-specific notification sender. One bean per {@link MessagingKind}.
 *
 * Implementations receive the integration's *decrypted* config (map shape varies per
 * kind — webhook URL, bot token, etc.) and the rendered {@link NotificationMessage}.
 * Failures are propagated; the dispatcher catches per-binding so one broken transport
 * doesn't poison the rest of the fan-out.
 */
public interface MessagingSender {
    MessagingKind kind();

    void send(Map<String, Object> decryptedConfig, NotificationMessage message) throws Exception;
}
