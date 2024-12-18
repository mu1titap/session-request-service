package com.multitab.sessionRequest.application.service;

import com.multitab.sessionRequest.adaptor.out.feignClient.PaymentServiceFeignClient;
import com.multitab.sessionRequest.adaptor.out.feignClient.dto.SessionResponseOutDto;
import com.multitab.sessionRequest.adaptor.out.feignClient.vo.SessionPaymentVo;
import com.multitab.sessionRequest.application.port.in.MentoringServiceCallUseCase;
import com.multitab.sessionRequest.application.port.in.RegisterSessionUserUseCase;
import com.multitab.sessionRequest.application.port.in.SendMessageUseCase;
import com.multitab.sessionRequest.application.port.in.SessionUserInquiryUseCase;
import com.multitab.sessionRequest.application.port.in.dto.RegisterSessionDto;
import com.multitab.sessionRequest.application.port.out.MentoringServiceCallOutPort;
import com.multitab.sessionRequest.application.port.out.SendMessageOutPort;
import com.multitab.sessionRequest.application.port.out.SessionUserRepositoryOutPort;
import com.multitab.sessionRequest.application.port.out.dto.out.SessionUserResponseOutDto;
import com.multitab.sessionRequest.application.port.out.dto.in.ReRegisterSessionOutDto;
import com.multitab.sessionRequest.application.port.out.dto.out.AfterSessionUserOutDto;
import com.multitab.sessionRequest.application.port.out.dto.in.RegisterSessionOutDto;
import com.multitab.sessionRequest.application.port.out.dto.out.ReRegisterSessionUserMessage;
import com.multitab.sessionRequest.common.entity.BaseResponse;
import com.multitab.sessionRequest.common.entity.BaseResponseStatus;
import com.multitab.sessionRequest.common.exception.BaseException;
import com.multitab.sessionRequest.domain.Status;
import com.multitab.sessionRequest.domain.model.SessionRequestDomain;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Log4j2
@RequiredArgsConstructor
@Service
@Transactional
public class RegisterSessionUserService implements RegisterSessionUserUseCase {
    private final MentoringServiceCallUseCase mentoringServiceCallUseCase;
    private final SessionUserInquiryUseCase sessionUserInquiryUseCase;
    private final SessionUserRepositoryOutPort sessionUserRepositoryOutPort;
    private final SendMessageOutPort sendMessageOutPort;
    private final PaymentServiceFeignClient paymentServiceFeignClient;

    //@Transactional(isolation = Isolation.SERIALIZABLE)
    @Override
    public void registerSessionUser(RegisterSessionDto dto) { // 멘토 uuid
        String uuid = dto.getSessionUuid();
        // 세션 상태 확인
        SessionResponseOutDto sessionResponseOut = mentoringServiceCallUseCase.getSessionOutDtoByUuid(uuid);
        log.info("sessionResponseOut : {}", sessionResponseOut);
        if( sessionResponseOut == null ) throw new BaseException(BaseResponseStatus.NO_MENTORING_SESSION_INFORMATION);
        // 세션 상태 검사 , 예약 마감일 검사
        SessionRequestDomain.isValidSessionState(sessionResponseOut.getIsClosed());
        SessionRequestDomain.isDeadlineValid(sessionResponseOut.getDeadlineDate());
        // 대기상태의 참가자 리스트 조회
        List<SessionUserResponseOutDto> sessionUserListOut =
                sessionUserInquiryUseCase.getSessionUserOutDtoBySessionUuid(uuid, Status.PENDING);
        // 최대 신청인원수 + 멘티중복신청 검사
        SessionRequestDomain.validateMenteeAndMaxHeadCount(sessionUserListOut, dto.getMenteeUuid(), sessionResponseOut.getMaxHeadCount());
        // 세션 참가신청 상태 확인 (이미 참가상태면 에러,취소 상태면 다시 대기상태로 업데이트)
        SessionUserResponseOutDto sessionUserResponse =
                sessionUserInquiryUseCase.getSessionUserOutDtoBySessionUuidAndMenteeUuid(uuid, dto.getMenteeUuid());
        // 결제 요청 data 생성
        SessionPaymentVo vo = SessionPaymentVo.builder()
            .sessionUuid(dto.getSessionUuid())
            .menteeUuid(dto.getMenteeUuid())
            .mentorUuid(null)   // 결합 끊기 위해 세션 완료 될 때 update
            .volt(dto.getVolt())
            .mentoringName(dto.getMentoringName())
            .nickname(dto.getNickName())
            .build();

        // 최초 세션 참가 신청 (insert)
        if( sessionUserResponse == null ) {
            // 결제 요청
            log.info("before FeignClient");
            BaseResponse<Void> response =
            paymentServiceFeignClient.paymentSession(vo);
            log.info("response: {}", response);
            log.info("after FeignClient");

            SessionRequestDomain domain =
                    SessionRequestDomain.createSessionRequestDomain(dto.getSessionUuid(), dto.getMenteeUuid(), dto.getMentoringName());
            AfterSessionUserOutDto afterSessionUserOutDto =
                    sessionUserRepositoryOutPort.registerSessionUser(RegisterSessionOutDto.from(domain));
            // 세션 참가 후 최대정원 다 찼는지 확인
            Boolean closedSession = domain.isClosedSession(sessionUserListOut.size(), sessionResponseOut.getMaxHeadCount());
            // 정원 다 찼으면 세션 command table update
            if(closedSession) mentoringServiceCallUseCase.closeSession(uuid);
            // "세션 참가등록" 메시지 발행
            setResiterSessionUserReadData(dto, afterSessionUserOutDto, closedSession);
            sendMessageOutPort.sendRegisterSessionUserMessage("register-session-user", afterSessionUserOutDto);
            log.info("신청 인서트");
        }
        // 취소 -> 대기상태로 업데이트 (취소했다가 다시 신청한 경우임)
        else if( sessionUserResponse.getStatus() == Status.CANCELLED_BY_USER ) {
            // 결제 요청
            log.info("before FeignClient");
            BaseResponse<Void> response =
                paymentServiceFeignClient.paymentSession(vo);
            log.info("response: {}", response);
            log.info("after FeignClient");


            // 재등록
            SessionRequestDomain domain =
                    SessionRequestDomain.reCreateSessionRequestDomain(dto.getSessionUuid(), dto.getMenteeUuid(), sessionUserResponse.getId(), dto.getMentoringName());
            Integer count = sessionUserRepositoryOutPort.reRegisterSessionUser(ReRegisterSessionOutDto.from(domain));
            if( count > 0 ){
                Boolean closedSession = domain.isClosedSession(sessionUserListOut.size(), sessionResponseOut.getMaxHeadCount());
                boolean shouldCloseSession = false;
                if(closedSession) {
                    mentoringServiceCallUseCase.closeSession(uuid);
                    shouldCloseSession = true;
                }
                // "세션 참가 재등록" 메시지 발행
                sendMessageOutPort.sendReRegisterSessionUserMessage("re-register-session-user",
                        getReRegisterSessionUserMessage(dto, sessionResponseOut, shouldCloseSession));
            }
            log.info("신청 업데이트");
        }
    }

    private static void setResiterSessionUserReadData(RegisterSessionDto dto, AfterSessionUserOutDto afterSessionUserOutDto, Boolean closedSession) {
        afterSessionUserOutDto.setMentoringName(dto.getMentoringName());
        afterSessionUserOutDto.setIsClosed(closedSession);
        afterSessionUserOutDto.setMenteeImageUrl(dto.getUserImageUrl());
        afterSessionUserOutDto.setNickName(dto.getNickName());
        afterSessionUserOutDto.setMentorUuid(dto.getMentorUuid());
    }

    private  ReRegisterSessionUserMessage getReRegisterSessionUserMessage(RegisterSessionDto dto, SessionResponseOutDto sessionResponseOut, boolean shouldCloseSession) {
        return ReRegisterSessionUserMessage.builder()
                .sessionUuid(dto.getSessionUuid())
                .menteeUuid(dto.getMenteeUuid())
                .menteeImageUrl(dto.getUserImageUrl())
                .startDate(sessionResponseOut.getStartDate())
                .shouldCloseSession(shouldCloseSession)
                .build();
    }


}
