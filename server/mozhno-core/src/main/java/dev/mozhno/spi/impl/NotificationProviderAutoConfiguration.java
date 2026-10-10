package dev.mozhno.spi.impl;

import dev.mozhno.mail.MailProperties;
import dev.mozhno.spi.NotificationSpi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Registers exactly one {@link NotificationSpi}: SMTP delivery when a
 * {@link JavaMailSender} is available, otherwise the log-only fallback.
 *
 * <p>Declared as an auto-configuration instead of scanned {@code @Component}s so the
 * {@code @ConditionalOnBean}/{@code @ConditionalOnMissingBean} checks are evaluated
 * after {@link MailSenderAutoConfiguration} contributed the mail sender. When the
 * providers were component-scanned, the checks ran before auto-configuration and
 * always selected the logging fallback, so SMTP failures stayed invisible.
 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
public class NotificationProviderAutoConfiguration {

    @Bean
    @ConditionalOnBean(JavaMailSender.class)
    @ConditionalOnMissingBean(NotificationSpi.class)
    public NotificationSpi smtpNotificationProvider(JavaMailSender mailSender,
                                                     MailProperties mailProperties) {
        return new SmtpNotificationProvider(mailSender, mailProperties);
    }

    @Bean
    @ConditionalOnMissingBean(value = {JavaMailSender.class, NotificationSpi.class})
    public NotificationSpi loggingNotificationProvider() {
        return new LoggingNotificationProvider();
    }
}
