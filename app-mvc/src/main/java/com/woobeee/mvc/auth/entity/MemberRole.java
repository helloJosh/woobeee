package com.woobeee.mvc.auth.entity;

public enum MemberRole {
    MEMBER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
