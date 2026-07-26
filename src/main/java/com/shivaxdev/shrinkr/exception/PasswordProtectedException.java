package com.shivaxdev.shrinkr.exception;

public class PasswordProtectedException extends RuntimeException {

    private final String slug;

    public PasswordProtectedException(String slug) {
        super("Link is password protected");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
