package dev.mozhno.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.mozhno.spi.AuthenticationFlowSpi;

import dev.mozhno.exception.InvalidCredentialsException;
import dev.mozhno.exception.NotFoundException;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Handles authentication workflows including login, token refresh, and logout.
 *
 * <p>Login is delegated to registered {@link dev.mozhno.spi.AuthenticationFlowSpi}
 * implementations. The first flow that {@link dev.mozhno.spi.AuthenticationFlowSpi#supports supports}
 * the request is used for authentication. On success, a JWT access token and refresh token
 * pair is issued via {@link RefreshTokenService}.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final List<AuthenticationFlowSpi> authFlows;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;

    public AuthService(List<AuthenticationFlowSpi> authFlows,
                       JwtService jwtService, RefreshTokenService refreshTokenService,
                       UserRepository userRepository) {
        this.authFlows = authFlows.stream()
            .sorted(Comparator.comparingInt(AuthenticationFlowSpi::priority))
            .toList();
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
    }

    /**
     * Authenticates with email and password, then issues a token pair.
     *
     * @param email      user email
     * @param password   plaintext password
     * @param rememberMe whether to issue a long-lived refresh token
     * @return login response containing access token, refresh token, and user DTO
     */
    public LoginResponse login(String email, String password, boolean rememberMe) {
        return login(email, password, null, Collections.emptyMap(), rememberMe);
    }

    /**
     * Authenticates with email, password, and optional provider parameters, then issues a token pair.
     * Authenticates with email, password, and optional provider parameters, then issues a token pair.
     *
     * @param email      user email
     * @param password   plaintext password
     * @param provider   authentication provider name (e.g. "ldap", "sso"), or null
     * @param params     additional provider-specific parameters
     * @param rememberMe whether to issue a long-lived refresh token
     * @return login response containing access token, refresh token, and user DTO
     */
    public LoginResponse login(String email, String password, String provider,
                                java.util.Map<String, String> params, boolean rememberMe) {
        AuthenticationFlowSpi.AuthRequest request = new AuthenticationFlowSpi.AuthRequest(
            email, password, provider, params);

        AuthenticationFlowSpi.AuthResult result = null;

        for (AuthenticationFlowSpi flow : authFlows) {
            if (flow.supports(request)) {
                result = flow.authenticate(request);
                if (result.success()) {
                    break;
                }
            }
        }

        if (result == null || !result.success()) {
            log.warn("Login failed for email={} provider={}", email, provider);
            throw new InvalidCredentialsException(
                result != null ? result.errorMessage() : "auth.error.no_auth_provider");
        }

        User user = userRepository.findById(result.userId());
        RefreshTokenService.TokenPair tokens = refreshTokenService.issueTokens(user, user.getProjectId(), rememberMe);
        userRepository.touchActivity(user.getId());
        return new LoginResponse(tokens.getAccessToken(), tokens.getRefreshToken(), toDto(user));
    }

    /**
     * Exchanges a valid refresh token for a new access/refresh token pair.
     *
     * @param rawRefreshToken the raw refresh token string
     * @param projectId       the project ID from the old JWT, or null
     * @return login response with fresh tokens and current user DTO
     */
    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        RefreshTokenService.TokenPair tokens = refreshTokenService.refresh(rawRefreshToken);
        String email = jwtService.parseToken(tokens.getAccessToken()).getEmail();
        User user = userRepository.findByEmail(email);
        userRepository.touchActivity(user.getId());
        return new LoginResponse(tokens.getAccessToken(), tokens.getRefreshToken(), toDto(user));
    }

    /**
     * Revokes the refresh token and its family, effectively logging the user out.
     *
     * @param rawRefreshToken the refresh token to revoke (ignored if null or empty)
     */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isEmpty()) {
            refreshTokenService.revoke(rawRefreshToken);
        }
    }

    /**
     * Returns the DTO for the currently authenticated user.
     *
     * @param email email address of the authenticated user
     * @return user DTO
     */
    public UserDto getCurrentUser(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new NotFoundException("User not found");
        }
        return toDto(user);
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getStatus(), user.getAvatar(), user.getLocale(), user.getCreatedAt(), user.getLastActiveAt());
    }
}
