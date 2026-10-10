package dev.mozhno.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.mail.EmailTemplateService;
import dev.mozhno.spi.NotificationSpi;
import dev.mozhno.exception.NotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static dev.mozhno.client.HashUtils.generateRawToken;
import static dev.mozhno.client.HashUtils.sha256;

@Service
public class PasswordResetService {

    private final Cache<String, Instant> lastResetSent;

    private boolean tryAcquireCooldown(String email) {
        Instant now = Instant.now();
        Instant previous = lastResetSent.asMap().putIfAbsent(email, now);
        if (previous == null) {
            return true;
        }
        if (previous.plus(authProperties.getPasswordReset().getCooldownMinutes(), ChronoUnit.MINUTES).isAfter(now)) {
            return false;
        }
        lastResetSent.put(email, now);
        return true;
    }

    private final EmailTemplateService emailTemplateService;
    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final NotificationSpi notificationSpi;
    private final DomainEventPublisher events;
    private final AuthProperties authProperties;
    private final String baseUrl;

    public PasswordResetService(EmailTemplateService emailTemplateService,
                                PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService,
                                NotificationSpi notificationSpi,
                                DomainEventPublisher events,
                                AuthProperties authProperties,
                                dev.mozhno.config.MozhnoProperties mozhnoProperties) {
        this.emailTemplateService = emailTemplateService;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.notificationSpi = notificationSpi;
        this.events = events;
        this.authProperties = authProperties;
        this.baseUrl = mozhnoProperties.getBaseUrl();
        this.lastResetSent = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(authProperties.getPasswordReset().getCooldownMinutes() + 1L))
            .maximumSize(10_000)
            .build();
    }

    @Transactional
    public boolean sendResetEmail(String email, String locale) {
        String effectiveLocale = locale != null ? locale : "ru";

        User user = userRepository.findByEmail(email);
        if (user == null || "suspended".equals(user.getStatus()) || "invited".equals(user.getStatus())) {
            return false;
        }

        if (!tryAcquireCooldown(email)) {
            return false;
        }

        tokenRepository.markAllUsedForUser(user.getId());

        String rawToken = generateRawToken();
        String tokenHash = sha256(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(tokenHash);
        token.setExpiresAt(Instant.now().plus(authProperties.getPasswordReset().getTokenTtlHours(), ChronoUnit.HOURS));
        tokenRepository.save(token);

        String resetLink = baseUrl + "/reset-password#token=" + rawToken;
        EmailTemplateService.EmailTemplate template = emailTemplateService.renderResetPasswordEmail(resetLink, effectiveLocale);

        notificationSpi.send(new NotificationSpi.NotificationEvent(
            "EMAIL", email, template.subject(), template.html(), null));

        events.publish(DomainEvent.of(null, "password_reset.requested", "user",
            user.getId(), user.getEmail(), "Password reset email sent"));
        return true;
    }

    @Transactional
    public void sendAdminResetEmail(Integer userId, Integer projectId) {
        User user = userRepository.findById(userId);
        if (user == null || "suspended".equals(user.getStatus())
            || user.getProjectId() == null || !user.getProjectId().equals(projectId)) {
            throw new NotFoundException("User", userId);
        }

        String locale = user.getLocale() != null ? user.getLocale() : "ru";

        tokenRepository.markAllUsedForUser(userId);

        String rawToken = generateRawToken();
        String tokenHash = sha256(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(userId);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(Instant.now().plus(authProperties.getPasswordReset().getTokenTtlHours(), ChronoUnit.HOURS));
        tokenRepository.save(token);

        String resetLink = baseUrl + "/reset-password#token=" + rawToken;
        EmailTemplateService.EmailTemplate template = emailTemplateService.renderAdminResetPasswordEmail(resetLink, locale);

        notificationSpi.send(new NotificationSpi.NotificationEvent(
            "EMAIL", user.getEmail(), template.subject(), template.html(), null));

        events.publish(DomainEvent.of(null, "password_reset.admin_requested", "user",
            userId, user.getEmail(), "Admin password reset email sent"));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);
        PasswordResetToken token = tokenRepository.findByHashForUpdate(tokenHash);

        if (token == null) {
            throw new InvalidTokenException("Invalid or expired reset token");
        }
        if (token.getUsedAt() != null) {
            throw new InvalidTokenException("Token has already been used");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Reset token has expired");
        }

        PasswordValidator.validate(newPassword, null);

        User user = userRepository.findById(token.getUserId());
        if (user == null) {
            throw new InvalidTokenException("User not found");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setStatus("active");
        userRepository.save(user);

        userRepository.resetFailedAttempts(user.getId());
        refreshTokenService.revokeAllForUser(user.getId());
        tokenRepository.markUsed(token.getId());

        events.publish(DomainEvent.of(null, "password_reset.completed", "user",
            user.getId(), user.getEmail(), "Password reset completed"));
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
