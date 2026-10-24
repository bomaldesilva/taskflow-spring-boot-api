package com.example.taskflow.repository;

import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.Objects;

public final class TaskSpecifications {

    private TaskSpecifications() {
    }

    /** Every task list starts with the authenticated owner, even without filters. */
    public static Specification<Task> matching(
            Long ownerId, TaskStatus status, Integer priority, String title) {
        return ownedBy(ownerId)
                .and(withStatus(status))
                .and(withPriority(priority))
                .and(withTitleContaining(title));
    }

    /** Preserve the deprecated derived query's database-side case conversion. */
    public static Specification<Task> matchingLegacyTitle(Long ownerId, String title) {
        Objects.requireNonNull(title, "Title is required.");
        String pattern = literalPattern(title);
        return ownedBy(ownerId).and((root, query, builder) -> builder.like(
                builder.upper(root.get("title")), builder.upper(builder.literal(pattern)), '!'));
    }

    public static Specification<Task> ownedBy(Long ownerId) {
        Objects.requireNonNull(ownerId, "An owner ID is required.");
        return (root, query, builder) -> builder.equal(root.get("owner").get("id"), ownerId);
    }

    public static Specification<Task> withStatus(TaskStatus status) {
        if (status == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Task> withPriority(Integer priority) {
        if (priority == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> builder.equal(root.get("priority"), priority);
    }

    public static Specification<Task> withTitleContaining(String title) {
        if (title == null) {
            return Specification.unrestricted();
        }
        String pattern = literalPattern(title.toLowerCase(Locale.ROOT));
        return (root, query, builder) -> builder.like(builder.lower(root.get("title")), pattern, '!');
    }

    private static String literalPattern(String title) {
        // Treat title input as literal text; only these surrounding % characters are wildcards.
        return "%" + title
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }
}
