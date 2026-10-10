package dev.mozhno.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.exception.BadRequestException;
import dev.mozhno.exception.ConflictException;
import dev.mozhno.exception.NotFoundException;

import dev.mozhno.util.MediaTypeUtils;

import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;

/**
 * Service for user management (CRUD).
 *
 * <p>Domain events are published on update and delete for audit
 * and integration purposes. User creation is handled exclusively through
 * the invite flow — see {@link UserInviteService}.</p>
 */
@Service
public class UserService {

    private static final long MAX_AVATAR_PIXELS = 1024L * 1024L;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher events;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       DomainEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    /**
     * Returns all users of the given project as DTOs.
     *
     * @param projectId owning project id
     * @return list of user DTOs
     */
    @Transactional(readOnly = true)
    public List<UserDto> findAll(Integer projectId) {
        return userRepository.findByProjectId(projectId).stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * Finds a user by id within the given project.
     *
     * @param id        user id
     * @param projectId owning project id
     * @return user DTO
     * @throws NotFoundException if the user does not exist in the project
     */
    @Transactional(readOnly = true)
    public UserDto findById(Integer id, Integer projectId) {
        return toDto(requireUserInProject(id, projectId));
    }

    /**
     * Updates an existing user. Only non-null fields in the request are applied.
     * Email and name are immutable after account creation — set at invite acceptance.
     *
     * @param id        user id
     * @param request   update payload (partial)
     * @param projectId owning project id
     * @return updated user DTO
     * @throws NotFoundException if the user does not exist in the project
     */
    @Transactional
    public UserDto update(Integer id, UserUpdateRequest request, Integer projectId) {
        User user = requireUserInProject(id, projectId);
        if (request.password() != null) {
            PasswordValidator.validate(request.password(), user.getEmail());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.role() != null) {
            if ("admin".equals(user.getRole()) && !"admin".equals(request.role())) {
                ensureGroupHasOtherAdmin(user);
            }
            user.setRole(request.role());
        }
        if (request.status() != null) user.setStatus(request.status());
        if (request.locale() != null) user.setLocale(request.locale());
        User saved = userRepository.save(user);
        events.publish(DomainEvent.of(null, "user.updated", "user",
            saved.getId(), saved.getEmail(), "Role: " + saved.getRole() + ", Status: " + saved.getStatus()));
        return toDto(saved);
    }

    /**
     * Deletes a user by id within the given project.
     *
     * @param id        user id
     * @param projectId owning project id
     * @throws NotFoundException if the user does not exist in the project
     */
    @Transactional
    public void delete(Integer id, Integer projectId) {
        User user = requireUserInProject(id, projectId);
        if ("admin".equals(user.getRole())) {
            ensureGroupHasOtherAdmin(user);
        }
        String email = user.getEmail();
        userRepository.delete(id);
        events.publish(DomainEvent.of(null, "user.deleted", "user",
            id, email, "User deleted"));
    }

    /**
     * Uploads an avatar image for a user, storing bytes in the database.
     *
     * @param id        the user ID
     * @param file      the uploaded image file
     * @param projectId owning project id
     * @return the updated user DTO
     * @throws NotFoundException if the user does not exist in the project
     */
    @Transactional
    public UserDto uploadAvatar(Integer id, MultipartFile file, Integer projectId) {
        User user = requireUserInProject(id, projectId);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Failed to read avatar file: " + e.getMessage());
        }
        MediaType type = MediaTypeUtils.detectRasterImageType(bytes);
        if (type == null) {
            throw new BadRequestException(
                "UNSUPPORTED_IMAGE_FORMAT",
                "Only PNG, JPEG, GIF or WEBP images are allowed");
        }
        int[] dims;
        try {
            dims = MediaTypeUtils.readDimensions(bytes);
        } catch (IOException e) {
            throw new BadRequestException(
                "IMAGE_READ_ERROR",
                "Failed to read image");
        }
        if (dims == null) {
            throw new BadRequestException(
                "IMAGE_READ_ERROR",
                "Failed to read image");
        }
        long pixels = (long) dims[0] * (long) dims[1];
        if (pixels > MAX_AVATAR_PIXELS) {
            throw new BadRequestException(
                "IMAGE_TOO_LARGE",
                dims[0] + "x" + dims[1] + " px. Max: 1024x1024 px");
        }
        userRepository.updateAvatarData(id, bytes);
        user.setAvatar("blob" + MediaTypeUtils.extensionFor(type));
        User saved = userRepository.save(user);
        events.publish(DomainEvent.of(null, "user.avatar_updated", "user",
            saved.getId(), saved.getEmail(), "Avatar updated"));
        return toDto(saved);
    }

    /**
     * Returns the avatar binary data for a user of the given project.
     *
     * @param id        the user ID
     * @param projectId owning project id
     * @return avatar bytes, or null if no avatar is set
     * @throws NotFoundException if the user does not exist in the project
     */
    @Transactional(readOnly = true)
    public byte[] getAvatarData(Integer id, Integer projectId) {
        User user = requireUserInProject(id, projectId);
        if (user.getAvatar() == null || user.getAvatar().isEmpty()) {
            return null;
        }
        return userRepository.getAvatarData(id);
    }

    private User requireUserInProject(Integer id, Integer projectId) {
        User user = userRepository.findById(id);
        if (user == null || user.getProjectId() == null
            || !user.getProjectId().equals(projectId)) {
            throw new NotFoundException("User", id);
        }
        return user;
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getStatus(), user.getAvatar(), user.getLocale(), user.getCreatedAt(), user.getLastActiveAt());
    }

    private void ensureGroupHasOtherAdmin(User user) {
        int children = userRepository.countByCreatedBy(user.getId());
        if (children > 0) {
            int otherAdmins = userRepository.countAdminsCreatedBy(user.getId(), user.getId());
            if (otherAdmins == 0) {
                throw new ConflictException(
                    "Cannot remove the last admin from a group that has members. Promote another member to admin first.");
            }
        }
    }
}