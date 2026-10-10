package dev.mozhno.spi.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import dev.mozhno.spi.NotificationSpi;

/**
 * Fallback {@link NotificationSpi} implementation that writes notification
 * events to the application log via SLF4J instead of delivering them through
 * external channels.
 *
 * <p>Registered by {@link NotificationProviderAutoConfiguration} when no
 * {@link JavaMailSender} is available (i.e. no SMTP configured).
 */
public class LoggingNotificationProvider implements NotificationSpi {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationProvider.class);

    /**
     * Logs the notification event at INFO level.
     *
     * @param event the notification event containing type, recipient, subject,
     *              and body
     * @implNote The OSS implementation logs via SLF4J at INFO level. No
     *           external delivery (email, push, webhook) is performed.
     */
    @Override
    public void send(NotificationEvent event) {
        String maskedBody = event.body() != null && event.body().length() > 200
            ? event.body().substring(0, 200) + "..."
            : event.body();
        log.info("Notification [{}] to {}: {}", event.type(), event.recipient(), event.subject());
        log.debug("Notification body: {}", maskedBody);
    }
}
