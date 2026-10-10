package dev.mozhno.auth;

import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.mail.EmailTemplateService;
import dev.mozhno.projects.DefaultProjectFactory;
import dev.mozhno.projects.Project;
import dev.mozhno.spi.NotificationSpi;
import dev.mozhno.spi.QuotaSpi;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserInviteServiceTest {

    @Mock
    private EmailTemplateService emailTemplateService;

    @Mock
    private InviteTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationSpi notificationSpi;

    @Mock
    private DomainEventPublisher events;

    @Mock
    private QuotaSpi quotaSpi;

    @Mock
    private DefaultProjectFactory projectFactory;

    @org.mockito.Spy
    private AuthProperties authProperties = new AuthProperties();

    @org.mockito.Spy
    private dev.mozhno.config.MozhnoProperties mozhnoProperties = new dev.mozhno.config.MozhnoProperties();

    @InjectMocks
    private UserInviteService userInviteService;

    @BeforeEach
    void setUp() {
        lenient().when(projectFactory.createProject(any(), any())).thenAnswer(inv -> {
            Project p = new Project();
            p.setId(1);
            return p;
        });
    }

    private InviteUserRequest createRequest(String email, String role) {
        return new InviteUserRequest(email, role, null);
    }

    private InviteUserRequest createRequest(String email, String role, String locale) {
        return new InviteUserRequest(email, role, locale);
    }

    @Test
    void inviteUser_emailExists_shouldThrow() {
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userInviteService.inviteUser(
            createRequest("existing@example.com", "developer"), 1))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void inviteUser_quotaBlocked_shouldThrow() {
        User inviter = new User();
        inviter.setId(1);
        inviter.setProjectId(7);
        when(userRepository.findById(1)).thenReturn(inviter);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(quotaSpi.canCreateUser(7)).thenReturn(new QuotaSpi.Blocked("users", 10, 10, "Free"));

        assertThatThrownBy(() -> userInviteService.inviteUser(
            createRequest("new@example.com", "developer"), 1))
            .isInstanceOf(dev.mozhno.exception.QuotaExceededException.class)
            .hasMessageContaining("limit reached");
    }

    @Test
    void inviteUser_success_shouldStoreTokenAndSendEmail() {
        User inviter = new User();
        inviter.setId(42);
        inviter.setProjectId(7);
        when(userRepository.findById(42)).thenReturn(inviter);
        when(userRepository.existsByEmail("invitee@example.com")).thenReturn(false);
        when(quotaSpi.canCreateUser(7)).thenReturn(new QuotaSpi.Allowed());
        when(emailTemplateService.renderInviteEmail(anyString(), anyString())).thenReturn(new EmailTemplateService.EmailTemplate("Приглашение в можно", "<html>invite</html>"));
        when(tokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> {
            InviteToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        userInviteService.inviteUser(createRequest("invitee@example.com", "developer"), 42);

        ArgumentCaptor<InviteToken> tokenCaptor = ArgumentCaptor.forClass(InviteToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        InviteToken saved = tokenCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("invitee@example.com");
        assertThat(saved.getRole()).isEqualTo("developer");
        assertThat(saved.getCreatedBy()).isEqualTo(42);
        assertThat(saved.getTokenHash()).isNotEmpty();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        NotificationSpi.NotificationEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("EMAIL");
        assertThat(event.recipient()).isEqualTo("invitee@example.com");
        assertThat(event.subject()).isEqualTo("Приглашение в можно");
        assertThat(event.body()).isEqualTo("<html>invite</html>");

        verify(quotaSpi).canCreateUser(7);
        verify(events).publish(any(DomainEvent.class));
    }

    @Test
    void inviteUser_englishLocale_shouldUseEnglishSubjectAndTemplate() {
        when(userRepository.existsByEmail("eng@example.com")).thenReturn(false);
        when(quotaSpi.canCreateUser(null)).thenReturn(new QuotaSpi.Allowed());
        when(emailTemplateService.renderInviteEmail(anyString(), eq("en"))).thenReturn(new EmailTemplateService.EmailTemplate("Invitation to Mozhno", "<html>invite-en</html>"));
        when(tokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> {
            InviteToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        userInviteService.inviteUser(createRequest("eng@example.com", "developer", "en"), 42);

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().subject()).isEqualTo("Invitation to Mozhno");
        assertThat(eventCaptor.getValue().body()).isEqualTo("<html>invite-en</html>");
    }

    @Test
    void inviteUser_defaultLocale_shouldBeRussian() {
        when(userRepository.existsByEmail("def@example.com")).thenReturn(false);
        when(quotaSpi.canCreateUser(null)).thenReturn(new QuotaSpi.Allowed());
        when(emailTemplateService.renderInviteEmail(anyString(), eq("ru"))).thenReturn(new EmailTemplateService.EmailTemplate("Приглашение в можно", "<html>invite-ru</html>"));
        when(tokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> {
            InviteToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        userInviteService.inviteUser(createRequest("def@example.com", "developer"), 42);

        ArgumentCaptor<NotificationSpi.NotificationEvent> eventCaptor =
            ArgumentCaptor.forClass(NotificationSpi.NotificationEvent.class);
        verify(notificationSpi).send(eventCaptor.capture());
        assertThat(eventCaptor.getValue().subject()).isEqualTo("Приглашение в можно");
    }

    @Test
    void inviteUser_inviteLink_shouldNotHaveAuthPrefix() {
        when(userRepository.existsByEmail("link@example.com")).thenReturn(false);
        when(quotaSpi.canCreateUser(null)).thenReturn(new QuotaSpi.Allowed());
        when(emailTemplateService.renderInviteEmail(anyString(), anyString())).thenAnswer(inv -> new EmailTemplateService.EmailTemplate("subj", inv.getArgument(0)));
        when(tokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> {
            InviteToken t = inv.getArgument(0);
            t.setId(1);
            return t;
        });

        userInviteService.inviteUser(createRequest("link@example.com", "viewer"), 1);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailTemplateService).renderInviteEmail(linkCaptor.capture(), anyString());
        String link = linkCaptor.getValue();
        assertThat(link).contains("/accept-invite#token=");
        assertThat(link).doesNotContain("/auth/accept-invite");
    }

    @Test
    void acceptInvite_invalidToken_shouldThrow() {
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(null);

        assertThatThrownBy(() -> userInviteService.acceptInvite("bad-token", "Name", "password1"))
            .isInstanceOf(UserInviteService.InvalidInviteTokenException.class)
            .hasMessageContaining("Invalid or expired");
    }

    @Test
    void acceptInvite_alreadyUsedToken_shouldThrow() {
        InviteToken token = new InviteToken();
        token.setId(1);
        token.setUsedAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);

        assertThatThrownBy(() -> userInviteService.acceptInvite("used-token", "Name", "password1"))
            .isInstanceOf(UserInviteService.InvalidInviteTokenException.class)
            .hasMessageContaining("already been used");
    }

    @Test
    void acceptInvite_expiredToken_shouldThrow() {
        InviteToken token = new InviteToken();
        token.setId(1);
        token.setExpiresAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);

        assertThatThrownBy(() -> userInviteService.acceptInvite("expired-token", "Name", "password1"))
            .isInstanceOf(UserInviteService.InvalidInviteTokenException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void acceptInvite_newUser_shouldCreateUserAndMarkUsed() {
        InviteToken token = new InviteToken();
        token.setId(10);
        token.setEmail("newuser@example.com");
        token.setRole("developer");
        token.setCreatedBy(42);
        token.setLocale("en");
        token.setExpiresAt(Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findByEmail("newuser@example.com")).thenReturn(null);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(100);
            return u;
        });

        UserDto result = userInviteService.acceptInvite("valid-token", "John Doe", "Password123!");

        assertThat(result.email()).isEqualTo("newuser@example.com");
        assertThat(result.name()).isEqualTo("John Doe");
        assertThat(result.role()).isEqualTo("developer");
        assertThat(result.status()).isEqualTo("active");
        assertThat(result.locale()).isEqualTo("en");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("newuser@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(saved.getName()).isEqualTo("John Doe");
        assertThat(saved.getRole()).isEqualTo("developer");
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getLocale()).isEqualTo("en");
        assertThat(saved.getCreatedBy()).isEqualTo(42);
        assertThat(saved.getProjectId()).isEqualTo(1);

        verify(projectFactory).createProject(null, "");
        verify(tokenRepository).markUsed(10);
        verify(events).publish(any(DomainEvent.class));
    }

    @Test
    void acceptInvite_newUser_shouldUseInviterProject() {
        User inviter = new User();
        inviter.setId(99);
        inviter.setProjectId(5);

        InviteToken token = new InviteToken();
        token.setId(20);
        token.setEmail("child@example.com");
        token.setRole("developer");
        token.setCreatedBy(99);
        token.setExpiresAt(Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findById(99)).thenReturn(inviter);
        when(userRepository.findByEmail("child@example.com")).thenReturn(null);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userInviteService.acceptInvite("invite-token", "Child", "Password123!");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getProjectId()).isEqualTo(5);
        assertThat(userCaptor.getValue().getCreatedBy()).isEqualTo(99);
    }

    @Test
    void acceptInvite_existingInvitedUser_shouldActivate() {
        User existing = new User();
        existing.setId(50);
        existing.setEmail("existing@example.com");
        existing.setStatus("invited");
        existing.setName("Old Name");

        InviteToken token = new InviteToken();
        token.setId(11);
        token.setEmail("existing@example.com");
        token.setRole("developer");
        token.setCreatedBy(77);
        token.setExpiresAt(Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findByEmail("existing@example.com")).thenReturn(existing);
        when(passwordEncoder.encode("Newpass1!")).thenReturn("hashed-newpass");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto result = userInviteService.acceptInvite("valid-token", "Updated Name", "Newpass1!");

        assertThat(result.email()).isEqualTo("existing@example.com");
        assertThat(result.name()).isEqualTo("Updated Name");
        assertThat(result.status()).isEqualTo("active");
        assertThat(result.role()).isEqualTo("developer");

        assertThat(existing.getPasswordHash()).isEqualTo("hashed-newpass");
        assertThat(existing.getStatus()).isEqualTo("active");
        assertThat(existing.getRole()).isEqualTo("developer");
        assertThat(existing.getCreatedBy()).isEqualTo(77);

        verify(tokenRepository).markUsed(11);
    }

    @Test
    void acceptInvite_existingActiveUser_shouldThrowConflict() {
        User existing = new User();
        existing.setId(50);
        existing.setEmail("active@example.com");
        existing.setStatus("active");

        InviteToken token = new InviteToken();
        token.setId(12);
        token.setEmail("active@example.com");
        token.setRole("viewer");
        token.setExpiresAt(Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findByEmail("active@example.com")).thenReturn(existing);

        assertThatThrownBy(() -> userInviteService.acceptInvite("valid-token", "Name", "Password1!"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("cannot accept invite");

        verify(tokenRepository, never()).markUsed(anyInt());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void acceptInvite_nullName_shouldUseEmail() {
        InviteToken token = new InviteToken();
        token.setId(12);
        token.setEmail("noname@example.com");
        token.setRole("viewer");
        token.setExpiresAt(Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS));

        when(tokenRepository.findByHashForUpdate(anyString())).thenReturn(token);
        when(userRepository.findByEmail("noname@example.com")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(101);
            return u;
        });

        UserDto result = userInviteService.acceptInvite("valid-token", null, "Password1!");

        assertThat(result.name()).isEqualTo("noname@example.com");
    }
}