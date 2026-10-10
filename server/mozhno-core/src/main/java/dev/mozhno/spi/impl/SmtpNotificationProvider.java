package dev.mozhno.spi.impl;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import dev.mozhno.mail.MailDeliveryException;
import dev.mozhno.mail.MailProperties;
import dev.mozhno.spi.NotificationSpi;

/**
 * {@link NotificationSpi} implementation that delivers EMAIL events through
 * SMTP. Registered by {@link NotificationProviderAutoConfiguration} when a
 * {@link JavaMailSender} is available. Delivery failures throw
 * {@link MailDeliveryException} so callers fail visibly instead of pretending
 * the notification was sent.
 */
public class SmtpNotificationProvider implements NotificationSpi {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotificationProvider.class);

    private final JavaMailSender mailSender;
    private final String emailFrom;

    public SmtpNotificationProvider(JavaMailSender mailSender,
                                    MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.emailFrom = mailProperties.getFrom();
    }

    @Override
    public void send(NotificationEvent event) {
        if (!"EMAIL".equalsIgnoreCase(event.type())) {
            log.info("Notification [{}] to {}: {} — {} (non-email, logged only)",
                event.type(), event.recipient(), event.subject(), event.body());
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailFrom);
            helper.setTo(event.recipient());
            helper.setSubject(event.subject());
            helper.setText(event.body(), true);
            mailSender.send(message);
            log.info("Email sent to {}: {}", event.recipient(), event.subject());
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send email to {}: {}", event.recipient(), e.getMessage(), e);
            throw new MailDeliveryException("Failed to send email to " + event.recipient(), e);
        }
    }
}
