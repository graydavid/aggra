package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

public class RoleTest {

    @Test
    public void ofThrowsExceptionGivenNullAndBlankNames() {
        assertThrows(NullPointerException.class, () -> Role.of(null));
        assertThrows(IllegalArgumentException.class, () -> Role.of(""));
        assertThrows(IllegalArgumentException.class, () -> Role.of(" "));
    }

    @Test
    public void ofThrowsExceptionsWithCodePointsForErroneousStringForEasierDebugging() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Role.of(" \u001C\u001F"));
        String message = exception.getMessage();

        String expectedCodePoints = "[32, 28, 31]";
        assertThat(message, containsString(expectedCodePoints));
    }

    @Test
    public void ofAllowsAnyNonBlankNames() {
        Role.of("amzAmz049");
        Role.of(".");
        Role.of(":");
        Role.of("_");
        Role.of("-");
        Role.of("/");
        String longRandom = RandomStringUtils.random(1000);
        try {
            Role.of(longRandom);
        } catch (RuntimeException e) {
            String codePoints = longRandom.codePoints().boxed().map(Object::toString).collect(Collectors.joining(","));
            throw new AssertionError("Failed to create role for code points: " + codePoints, e);
        }
    }

    @Test
    public void toStringReturnsName() {
        Role role = Role.of("name");

        assertThat(role.toString(), is("name"));
    }

    @Test
    public void equalsObeysContract() {
        Role role1 = Role.of("role1");
        Role role1Copy = Role.of("role1");
        Role role2 = Role.of("role2");

        assertThat(role1, equalTo(role1));
        assertThat(role1, equalTo(role1Copy));
        assertThat(role1Copy, equalTo(role1));
        assertThat(role1, not(equalTo(role2)));
        assertThat(role2, not(equalTo(role1)));
        assertThat(role1, not(equalTo(null)));
    }

    @Test
    public void hashCodeObeysContract() {
        Role role1 = Role.of("role1");
        Role role1Copy = Role.of("role1");

        assertThat(role1.hashCode(), equalTo(role1.hashCode()));
        assertThat(role1.hashCode(), equalTo(role1Copy.hashCode()));
    }

    @Test
    public void validatingFactoryCallsValidatorWhenCreatingValidRoles() {
        Role.Validator validator = mock(Role.Validator.class);
        Role.ValidatingFactory factory = new Role.ValidatingFactory(validator);

        Role role = factory.create("valid-role");

        verify(validator).validate(role);
        assertThat(role.toString(), is("valid-role"));
    }

    @Test
    public void validatingFactoryPassesAlongAnyExceptionsFromValidatorWhenCreatingInvalidRoles() {
        IllegalArgumentException exception = new IllegalArgumentException("Role is invalid");
        Role role = Role.of("invalid-role");
        Role.Validator validator = mock(Role.Validator.class);
        doThrow(exception).when(validator).validate(role);
        Role.ValidatingFactory factory = new Role.ValidatingFactory(validator);

        IllegalArgumentException actualException = assertThrows(IllegalArgumentException.class,
                () -> factory.create("invalid-role"));

        assertThat(actualException, is(exception));
    }
}
