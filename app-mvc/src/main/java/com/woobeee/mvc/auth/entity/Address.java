package com.woobeee.mvc.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class Address {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String recipientName;   // 수령인
    private String phoneNumber;

    private String zipcode;
    private String address1;
    private String address2;

    private boolean isDefault;

    private LocalDateTime createdAt;
    private Long memberId;

}
