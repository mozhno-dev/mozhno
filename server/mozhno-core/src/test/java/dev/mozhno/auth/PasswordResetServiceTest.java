package dev.mozhno.auth;

import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.mail.EmailTemplateService;
import dev.mozhno.spi.NotificationSpi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private EmailTemplateService emailTemplateService;

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private NotificationSpi notificationSpi;

    @Mock
    private DomainEventPublisher events;

    @org.mockito.Spy
    private AuthProperties authProperties = new AuthProperties();

    @org.mockito.Spy
    private dev.mozhno.config.MozhnoProperties mozhnoProperties = new dev.mozhno.config.MozhnoProperties();

    @InjectMocks
    private PasswordResetService passwordResetService;

    private User createTestUser(Integer id, String status) {
        User user = new User();
        user.setId(id);
        user.setEmail("test" + id + "@example.com");
        user.setRole("developer");
        user.setStatus(status);
        user.setLocale("ru");
        user.setProjectId(1);
        return user;
    }

    @Test
    void sendResetEmail_userFound_shouldStoreTokenAndSendEmail() {
        User user = createTestUser(1, "active");
        when(userRepository.findByEmail("test@example.com")).thenReturn(user);
        when(emailTemplateService.renderResetPasswordEmail(anyString(), anyString())).thenReturn(new EmailTemplateService.EmailTemplate("Сброс пароля", "<html>reset</html>"));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        boolean result = passwordResetService.sendResetEmail("test@example.com", "ru");

        assertThat(result).isTrue();

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).markAllUsedForUser(1);
        verify(tokenRepository).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1);
        assertThat(saved.getTokenHash()).isNotEmpty();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        NotificationSpi.NotificationEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("EMAIL");
        assertThat(event.recipient()).isEqualTo("test@example.com");
        assertThat(event.subject()).isEqualTo("Сброс пароля");
        assertThat(event.body()).isEqualTo("<html>reset</html>");

        verify(events).publish(any(DomainEvent.class));
    }

    @Test
    void sendResetEmail_englishLocale_shouldUseEnglishSubject() {
        User user = createTestUser(2, "active");
        when(userRepository.findByEmail("eng@example.com")).thenReturn(user);
        when(emailTemplateService.renderResetPasswordEmail(anyString(), eq("en"))).thenReturn(new EmailTemplateService.EmailTemplate("Mozhno password reset", "<html>reset-en</html>"));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(2);
            return t;
        });

        passwordResetService.sendResetEmail("eng@example.com", "en");

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().subject()).isEqualTo("Mozhno password reset");
        assertThat(eventCaptor.getValue().body()).isEqualTo("<html>reset-en</html>");
    }

    @Test
    void sendResetEmail_userNotFound_shouldReturnFalse() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(null);

        boolean result = passwordResetService.sendResetEmail("nobody@example.com", "ru");

        assertThat(result).isFalse();
        verify(tokenRepository, never()).save(any());
        verify(notificationSpi, never()).send(any());
    }

    @Test
    void sendResetEmail_userSuspended_shouldReturnFalse() {
        User user = createTestUser(2, "suspended");
        when(userRepository.findByEmail("suspended@example.com")).thenReturn(user);

        boolean result = passwordResetService.sendResetEmail("suspended@example.com", "ru");

        assertThat(result).isFalse();
        verify(tokenRepository, never()).save(any());
        verify(notificationSpi, never()).send(any());
    }

    @Test
    void sendResetEmail_userInvited_shouldReturnFalse() {
        User user = createTestUser(3, "invited");
        when(userRepository.findByEmail("invited@example.com")).thenReturn(user);

        boolean result = passwordResetService.sendResetEmail("invited@example.com", "ru");

        assertThat(result).isFalse();
        verify(tokenRepository, never()).save(any());
        verify(notificationSpi, never()).send(any());
    }

    @Test
    void sendResetEmail_cooldown_shouldReturnFalse() {
        User user = createTestUser(1, "active");
        when(userRepository.findByEmail("cooldown@example.com")).thenReturn(user);
        when(emailTemplateService.renderResetPasswordEmail(anyString(), anyString())).thenReturn(new EmailTemplateService.EmailTemplate("Сброс пароля", "<html>reset</html>"));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        boolean first = passwordResetService.sendResetEmail("cooldown@example.com", "ru");
        assertThat(first).isTrue();

        boolean second = passwordResetService.sendResetEmail("cooldown@example.com", "ru");
        assertThat(second).isFalse();

        verify(tokenRepository, times(1)).save(any());
    }

    @Test
    void sendResetEmail_resetLink_shouldNotHaveAuthPrefix() {
        User user = createTestUser(3, "active");
        when(userRepository.findByEmail("link@example.com")).thenReturn(user);
        when(emailTemplateService.renderResetPasswordEmail(anyString(), anyString())).thenAnswer(inv -> new EmailTemplateService.EmailTemplate("Сброс пароля", inv.getArgument(0)));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(3);
            return t;
        });

        passwordResetService.sendResetEmail("link@example.com", "ru");

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailTemplateService).renderResetPasswordEmail(linkCaptor.capture(), anyString());
        String link = linkCaptor.getValue();
        assertThat(link).contains("/reset-password#token=");
        assertThat(link).doesNotContain("/auth/reset-password");
    }

    @Test
    void sendAdminResetEmail_shouldUseAdminTemplate() {
        User user = createTestUser(4, "active");
        user.setLocale("en");
        when(userRepository.findById(4)).thenReturn(user);
        when(emailTemplateService.renderAdminResetPasswordEmail(anyString(), eq("en"))).thenReturn(new EmailTemplateService.EmailTemplate("Mozhno password reset", "<html>admin-reset-en</html>"));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(4);
            return t;
        });

        passwordResetService.sendAdminResetEmail(4, 1);

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        NotificationSpi.NotificationEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("EMAIL");
        assertThat(event.recipient()).isEqualTo("test4@example.com");
        assertThat(event.subject()).isEqualTo("Mozhno password reset");
        assertThat(event.body()).isEqualTo("<html>admin-reset-en</html>");

        verify(emailTemplateService).renderAdminResetPasswordEmail(anyString(), eq("en"));
    }

    @Test
    void sendAdminResetEmail_userNotFound_shouldThrow() {
        when(userRepository.findById(999)).thenReturn(null);

        assertThatThrownBy(() -> passwordResetService.sendAdminResetEmail(999, 1))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("User");
    }

    @Test
    void sendAdminResetEmail_userSuspended_shouldThrow() {
        User user = createTestUser(5, "suspended");
        when(userRepository.findById(5)).thenReturn(user);

        assertThatThrownBy(() -> passwordResetService.sendAdminResetEmail(5, 1))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("User");
    }

    @Test
    void sendAdminResetEmail_userInAnotherProject_shouldThrow() {
        User user = createTestUser(7, "active");
        user.setProjectId(2);
        when(userRepository.findById(7)).thenReturn(user);

        assertThatThrownBy(() -> passwordResetService.sendAdminResetEmail(7, 1))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("User");
        verify(tokenRepository, never()).save(any());
        verify(notificationSpi, never()).send(any());
    }

    @Test
    void sendAdminResetEmail_shouldNotHaveAuthPrefix() {
        User user = createTestUser(6, "active");
        when(userRepository.findById(6)).thenReturn(user);
        when(emailTemplateService.renderAdminResetPasswordEmail(anyString(), anyString())).thenAnswer(inv -> new EmailTemplateService.EmailTemplate("Сброс пароля", inv.getArgument(0)));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            t.setId(6);
            return t;
        });

        passwordResetService.sendAdminResetEmail(6, 1);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailTemplateService).renderAdminResetPasswordEmail(linkCaptor.capture(), anyString());
        assertThat(linkCaptor.getValue()).contains("/reset-password#token=");
        assertThat(linkCaptor.getValue()).doesNotContain("/auth/reset-password");
    }

    @Test
    void resetPassword_invalidToken_shouldThrow() {
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(null);

        assertThatThrownBy(() -> passwordResetService.resetPassword("bad-token", "newpass1"))
            .isInstanceOf(PasswordResetService.InvalidTokenException.class)
            .hasMessageContaining("Invalid or expired");
    }

    @Test
    void resetPassword_alreadyUsedToken_shouldThrow() {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(1);
        token.setUserId(1);
        token.setUsedAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);

        assertThatThrownBy(() -> passwordResetService.resetPassword("used-token", "newpass1"))
            .isInstanceOf(PasswordResetService.InvalidTokenException.class)
            .hasMessageContaining("already been used");
    }

    @Test
    void resetPassword_expiredToken_shouldThrow() {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(1);
        token.setUserId(1);
        token.setExpiresAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);

        assertThatThrownBy(() -> passwordResetService.resetPassword("expired-token", "newpass1"))
            .isInstanceOf(PasswordResetService.InvalidTokenException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void resetPassword_userNotFound_shouldThrow() {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(1);
        token.setUserId(999);
        token.setExpiresAt(Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS));
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findById(999)).thenReturn(null);

        assertThatThrownBy(() -> passwordResetService.resetPassword("valid-token", "Newpass1!"))
            .isInstanceOf(PasswordResetService.InvalidTokenException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    void resetPassword_success_shouldUpdatePasswordAndMarkUsed() {
        User user = createTestUser(1, "invited");
        PasswordResetToken token = new PasswordResetToken();
        token.setId(10);
        token.setUserId(1);
        token.setExpiresAt(Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findById(1)).thenReturn(user);
        when(passwordEncoder.encode("Newpassword1!")).thenReturn("hashed-password");

        passwordResetService.resetPassword("valid-token", "Newpassword1!");

        verify(passwordEncoder).encode("Newpassword1!");
        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(user.getStatus()).isEqualTo("active");

        verify(refreshTokenService).revokeAllForUser(1);
        verify(tokenRepository).markUsed(10);
        verify(events).publish(any(DomainEvent.class));
    }

    @Test
    void sha256_shouldProduceDeterministicHash() {
        String hash1 = dev.mozhno.client.HashUtils.sha256("test-input");
        String hash2 = dev.mozhno.client.HashUtils.sha256("test-input");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    void sha256_shouldProduceDifferentHashForDifferentInputs() {
        String hash1 = dev.mozhno.client.HashUtils.sha256("input-a");
        String hash2 = dev.mozhno.client.HashUtils.sha256("input-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}