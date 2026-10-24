package com.example.taskflow.security;

import com.example.taskflow.model.AppUser;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/** Carries the database identity whose credentials were actually checked. */
public final class AuthenticatedUser extends User {

    private static final long serialVersionUID = 1L;

    private final long userId;

    public AuthenticatedUser(AppUser user) {
        super(user.getUsername(), user.getPassword(), user.isEnabled(), true, true, true,
                AuthorityUtils.createAuthorityList("ROLE_" + user.getRole().name()));
        this.userId = user.getId();
    }

    public long getUserId() {
        return userId;
    }
}
