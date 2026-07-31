package com.woobeee.mvc.auth.entity;

public enum MemberType {
    BUYER("ROLE_BUYER"),
    SELLER("ROLE_SELLER");

    private final String roleName;

    MemberType(String roleName) {
        this.roleName = roleName;
    }

    public String roleName() {
        return roleName;
    }
}
