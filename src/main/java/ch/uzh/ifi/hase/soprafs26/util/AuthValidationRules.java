package ch.uzh.ifi.hase.soprafs26.util;

import java.util.regex.Pattern;

public final class AuthValidationRules {
    public static final int USERNAME_MIN_LENGTH = 1;
    public static final int USERNAME_MAX_LENGTH = 16;
    public static final String USERNAME_REGEX = "^[A-Za-z0-9]+$";
    public static final String USERNAME_ALLOWED_CHAR_REGEX = "[A-Za-z0-9]";
    public static final String USERNAME_HINT = "Username must be 1-16 characters and use only ASCII letters (A-Z, a-z) and digits (0-9).";

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 32;
    public static final String CREDENTIAL_FORMAT_REGEX = "^(?=.*[A-Z])(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{8,32}$";
    public static final String CREDENTIAL_ALLOWED_CHAR_REGEX = "[\\x21-\\x7E]";
    public static final String PASSWORD_HINT = "Password must be 8-32 characters, ASCII only (no spaces), include at least one uppercase letter and one special symbol.";

    public static final Pattern USERNAME_PATTERN = Pattern.compile(USERNAME_REGEX);
    public static final Pattern CREDENTIAL_FORMAT_PATTERN = Pattern.compile(CREDENTIAL_FORMAT_REGEX);

    private AuthValidationRules() {
    }
}
