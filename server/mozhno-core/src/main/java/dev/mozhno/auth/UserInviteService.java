package dev.mozhno.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.exception.ConflictException;
import dev.mozhno.exception.QuotaExceededException;
import dev.mozhno.mail.EmailTemplateService;
import dev.mozhno.projects.DefaultProjectFactory;
import dev.mozhno.projects.Project;
import dev.mozhno.spi.NotificationSpi;
import dev.mozhno.spi.QuotaSpi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static dev.mozhno.client.HashUtils.generateRawToken;
import static dev.mozhno.client.HashUtils.sha256;

@Service
public class UserInviteService {

    private final EmailTemplateService emailTemplateService;
    private final InviteTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationSpi notificationSpi;
    private final DomainEventPublisher events;
    private final QuotaSpi quotaSpi;
    private final AuthProperties authProperties;
    private final DefaultProjectFactory projectFactory;
    private final String baseUrl;

    public UserInviteService(EmailTemplateService emailTemplateService,
                             InviteTokenRepository tokenRepository,
                             UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             NotificationSpi notificationSpi,
                             DomainEventPublisher events,
                             QuotaSpi quotaSpi,
                             AuthProperties authProperties,
                             DefaultProjectFactory projectFactory,
                             dev.mozhno.config.MozhnoProperties mozhnoProperties) {
        this.emailTemplateService = emailTemplateService;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationSpi = notificationSpi;
        this.events = events;
        this.quotaSpi = quotaSpi;
        this.authProperties = authProperties;
        this.projectFactory = projectFactory;
        this.baseUrl = mozhnoProperties.getBaseUrl();
    }

    @Transactional
    public void inviteUser(InviteUserRequest request, Integer createdBy) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("User with email " + request.email() + " already exists");
        }

        QuotaSpi.QuotaResult quota = quotaSpi.canCreateUser(resolveInviterProject(createdBy));
        if (quota instanceof QuotaSpi.Blocked blocked) {
            throw new QuotaExceededException(blocked.current(), blocked.limit(), blocked.planName());
        }

        String rawToken = generateRawToken();
        String tokenHash = sha256(rawToken);
        String locale = request.locale() != null ? request.locale() : "ru";

        InviteToken token = new InviteToken();
        token.setEmail(request.email());
        token.setRole(request.role());
        token.setCreatedBy(createdBy);
        token.setTokenHash(tokenHash);
        token.setLocale(locale);
        token.setExpiresAt(Instant.now().plus(authProperties.getInvite().getTokenTtlDays(), ChronoUnit.DAYS));
        tokenRepository.save(token);

        String inviteLink = baseUrl + "/accept-invite#token=" + rawToken;
        EmailTemplateService.EmailTemplate template = emailTemplateService.renderInviteEmail(inviteLink, locale);

        notificationSpi.send(new NotificationSpi.NotificationEvent(
            "EMAIL", request.email(), template.subject(), template.html(), null));

        events.publish(DomainEvent.of(null, "user.invited", "user",
            null, request.email(), "Invitation sent for role: " + request.role()));
    }

    @Transactional
    public UserDto acceptInvite(String rawToken, String name, String password) {
        String tokenHash = sha256(rawToken);
        InviteToken token = tokenRepository.findByHashForUpdate(tokenHash);

        if (token == null) {
            throw new InvalidInviteTokenException("Invalid or expired invite link");
        }
        if (token.getUsedAt() != null) {
            throw new InvalidInviteTokenException("This invite link has already been used");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidInviteTokenException("Invite link has expired");
        }

        PasswordValidator.validate(password, token.getEmail());

        User user = userRepository.findByEmail(token.getEmail());
        if (user == null) {
            user = new User();
            user.setEmail(token.getEmail());
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setName(name != null ? name : token.getEmail());
            user.setRole(token.getRole());
            user.setStatus("active");
            user.setLocale(token.getLocale() != null ? token.getLocale() : "ru");
            user.setCreatedBy(token.getCreatedBy());
            Integer inviterProjectId = resolveInviterProject(token.getCreatedBy());
            if (inviterProjectId != null) {
                user.setProjectId(inviterProjectId);
            } else {
                Project project = projectFactory.createProject(null, "");
                user.setProjectId(project.getId());
            }
            userRepository.save(user);
        } else if ("invited".equals(user.getStatus())) {
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setName(name != null ? name : user.getName());
            user.setRole(token.getRole());
            user.setStatus("active");
            user.setCreatedBy(token.getCreatedBy());
            if (user.getProjectId() == null) {
                Integer inviterProjectId = resolveInviterProject(token.getCreatedBy());
                if (inviterProjectId != null) {
                    user.setProjectId(inviterProjectId);
                } else {
                    Project project = projectFactory.createProject(null, "");
                    user.setProjectId(project.getId());
                }
            }
            userRepository.save(user);
        } else {
            throw new ConflictException("User " + token.getEmail() + " cannot accept invite (status: " + user.getStatus() + ")");
        }

        tokenRepository.markUsed(token.getId());

        events.publish(DomainEvent.of(null, "user.invite_accepted", "user",
            user.getId(), user.getEmail(), "Invitation accepted, account activated"));

        return new UserDto(user.getId(), user.getEmail(), user.getName(),
            user.getRole(), user.getStatus(), user.getAvatar(), user.getLocale(),
            user.getCreatedAt(), user.getLastActiveAt());
    }

    private Integer resolveInviterProject(Integer inviterId) {
        if (inviterId == null) return null;
        User inviter = userRepository.findById(inviterId);
        if (inviter == null || inviter.getProjectId() == null) return null;
        return inviter.getProjectId();
    }

    public static class InvalidInviteTokenException extends RuntimeException {
        public InvalidInviteTokenException(String message) {
            super(message);
        }
    }
}