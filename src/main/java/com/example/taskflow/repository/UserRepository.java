package com.example.taskflow.repository;

import com.example.taskflow.model.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository
        extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppUser user set user.tokenVersion = user.tokenVersion + 1,
                                    user.rowVersion = user.rowVersion + 1
            where user.id = :id and user.tokenVersion = :version and user.enabled = true
            """)
    int revokeTokens(@Param("id") Long id, @Param("version") long version);
}
