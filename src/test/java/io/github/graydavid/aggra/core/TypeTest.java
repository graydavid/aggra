package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

public class TypeTest {

    @Test
    public void genericThrowsExceptionGivenNullAndBlankNames() {
        assertThrows(NullPointerException.class, () -> Type.generic(null));
        assertThrows(IllegalArgumentException.class, () -> Type.generic(""));
        assertThrows(IllegalArgumentException.class, () -> Type.generic(" "));
    }

    @Test
    public void genericThrowsExceptionsWithCodePointsForErroneousStringForEasierDebugging() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Type.generic(" \u001C\u001F"));
        String message = exception.getMessage();

        String expectedCodePoints = "[32, 28, 31]";
        assertThat(message, containsString(expectedCodePoints));
    }

    @Test
    public void genericAllowsAnyNonBlankNames() {
        Type.generic("amzAmz049");
        Type.generic(".");
        Type.generic(":");
        Type.generic("_");
        Type.generic("-");
        Type.generic("/");
        String longRandom = RandomStringUtils.random(1000);
        try {
            Type.generic(longRandom);
        } catch (RuntimeException e) {
            String codePoints = longRandom.codePoints().boxed().map(Object::toString).collect(Collectors.joining(","));
            throw new AssertionError("Failed to create type for code points: " + codePoints, e);
        }
    }

    @Test
    public void toStringReturnsName() {
        Type type = Type.generic("name");

        assertThat(type.toString(), is("name"));
    }

    @Test
    public void equalsObeysContract() {
        Type type1 = Type.generic("type1");
        Type type1Copy = Type.generic("type1");
        Type type2DifferentName = Type.generic("type2");
        Type type2DifferentClass = new TestType("type1");

        assertThat(type1, equalTo(type1));
        assertThat(type1, equalTo(type1Copy));
        assertThat(type1Copy, equalTo(type1));
        assertThat(type1, not(equalTo(type2DifferentName)));
        assertThat(type2DifferentName, not(equalTo(type1)));
        assertThat(type1, not(equalTo(type2DifferentClass)));
        assertThat(type2DifferentClass, not(equalTo(type1)));
        assertThat(type1, not(equalTo(null)));
    }

    private static class TestType extends Type {
        private TestType(String name) {
            super(name);
        }
    }

    @Test
    public void hashCodeObeysContract() {
        Type type1 = Type.generic("type1");
        Type type1Copy = Type.generic("type1");

        assertThat(type1.hashCode(), equalTo(type1.hashCode()));
        assertThat(type1.hashCode(), equalTo(type1Copy.hashCode()));
    }
}
