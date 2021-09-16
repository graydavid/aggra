/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * Describes the role that a Node or Caller plays in the calling of a graph of nodes: its unique purpose. Whereas Type
 * is the *how* a Node does something, the Role is the *what* it does. The main use case for roles is as a sort of
 * brief, "unique" name-like property for use cases like metricing. "unique" is in quotes, because there's no
 * enforcement of uniqueness across nodes, only that consumers of the role might want to treat roles as if they were
 * unique (e.g. recording metrics would result in duplicate recordings if role names were the same for different nodes).
 */
public class Role {
    private final String name;

    private Role(String name) {
        this.name = requireValidName(name);
    }

    private static String requireValidName(String name) {
        if (name.isBlank()) {
            StringJoiner codePoints = name.codePoints()
                    .collect(() -> new StringJoiner(", ", "[", "]"),
                            (joiner, point) -> joiner.add(String.valueOf(point)), StringJoiner::merge);
            throw new IllegalArgumentException(
                    "Role names must not be blank but found whitespace character in code points: " + codePoints);
        }
        return name;
    }

    /**
     * Creates a role with the given name. This method provides only base validation on the name. Depending on which
     * role consumers you want to use and their needs, which will determine how to construct a valid Role, you may want
     * to use {@link ValidatingFactory} with a custom {@link Validator} to create Roles instead.
     * 
     * @throws NullPointerException if name is null
     * @throws IllegalArgumentException if name is blank, as per {@link String#isBlank()}.
     */
    public static Role of(String name) {
        return new Role(name);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof Role)) {
            return false;
        }

        Role other = (Role) object;
        return Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    /**
     * Validates roles. This is useful idea to make sure that specific Role consumers will be able to successfully
     * handle a given Role instance. This validation concept cannot be baked directly into the Role class itself, since
     * there's no way to define one set of rules for every possible way that Role could be used... hence why Validator
     * exists separately.
     * 
     * As an example, if used for AWS metric names, the Role must have a length between 1 and 255
     * (https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_Metric.html). Clients can use
     * {@link ValidatingFactory} to create Roles in order to ensure this constraint and/or they can visit the graph post
     * creation and validate each Node's Role then. The latter strategy is wise if the former cannot be guaranteed.
     */
    public interface Validator {
        /**
         * Makes sure that role is valid.
         * 
         * @throws IllegalArgumentException if role is invalid.
         */
        void validate(Role role);
    }

    /** A way to create validated Roles. Combines {@link Role#of(String)} with {@link Validator}. */
    public static class ValidatingFactory {
        private final Validator validator;

        public ValidatingFactory(Validator validator) {
            this.validator = validator;
        }

        /**
         * Creates a role with the given name.
         * 
         * @throws IllegalArgumentException if the Role created from name is invalid according to the validator
         *         associated with this Factory.
         */
        public Role create(String name) {
            Role role = Role.of(name);
            validator.validate(role);
            return role;
        }
    }
}
