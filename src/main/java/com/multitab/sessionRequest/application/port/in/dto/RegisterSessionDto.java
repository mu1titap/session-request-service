package com.multitab.sessionRequest.application.port.in.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RegisterSessionDto {
    private String sessionUuid;
    private String mentorUuid;

    private Integer volt;
    private String menteeUuid;
    private String userImageUrl;
    private String nickName;

    private String mentoringName;


}
