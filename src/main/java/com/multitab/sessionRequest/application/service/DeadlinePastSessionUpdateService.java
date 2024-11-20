package com.multitab.sessionRequest.application.service;

import com.multitab.sessionRequest.adaptor.in.kafka.dto.DeadlinePastSessionResponseOutDto;
import com.multitab.sessionRequest.application.port.in.DeadlinePastSessionUpdateUseCase;
import com.multitab.sessionRequest.application.port.out.SendMessageOutPort;
import com.multitab.sessionRequest.application.port.out.SessionUserRepositoryOutPort;
import com.multitab.sessionRequest.application.port.out.dto.out.SessionConfirmedMessage;
import com.multitab.sessionRequest.application.port.out.dto.out.SessionUserResponseOutDto;
import com.multitab.sessionRequest.application.port.out.dto.out.SessionUserUpdateMessage;
import com.multitab.sessionRequest.domain.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Log4j2
@RequiredArgsConstructor
@Service
@Transactional
public class DeadlinePastSessionUpdateService implements DeadlinePastSessionUpdateUseCase {
    private final SessionUserRepositoryOutPort sessionUserRepositoryOutPort;
    private final SendMessageOutPort sendMessageOutPort;
    @Override
    public void updateSessionUser(DeadlinePastSessionResponseOutDto dto) {
        boolean sessionIsConfirmed; // 세션 진행 확정상태 여부
        // 대기 상태인 참가자 리스트 조회
        List<SessionUserResponseOutDto> pendingSessionUserList =
                sessionUserRepositoryOutPort.getPendingSessionUser(dto.getSessionUuid());
        // 대기 상태인 참가자 리스트의 id만 리스트로 추출
        List<String> sessionUserIdList = pendingSessionUserList.stream()
                .map(SessionUserResponseOutDto::getId)
                .toList();
        if (pendingSessionUserList.size() >= dto.getMinHeadCount()) { // 최소 인원 이상이면 세션 진행 확정
            sessionIsConfirmed = true;
        } else {
            sessionIsConfirmed = false;
        }
        // 참가자 리스트 상태 업데이트 (sessionIsConfirmed = true 면 [확정] false 면 [취소] )
        sessionUserRepositoryOutPort.updateSessionUserStatus(sessionUserIdList, sessionIsConfirmed);
        // 세션 확정 여부 메시지 전송
        sendMessageOutPort.sendConfirmSessionMessage("update-session-confirmed",
                getSessionConfirmedMessage(dto.getSessionUuid(), sessionIsConfirmed));

        List<SessionUserUpdateMessage> sessionUserUpdateMessageList =
                pendingSessionUserList.stream()
                .map(sessionUser ->
                        getSessionUserUpdateMessage(sessionUser.getMenteeUuid(), dto.getStartDate(),
                                sessionUser.getSessionUuid(), sessionIsConfirmed ? Status.CONFIRMED : Status.CANCELLED_BY_SYSTEM))
                .toList();
        // 유저마다 세션 참여 상태 업데이트 메시지 전송
        sessionUserUpdateMessageList.forEach(
                sessionUserUpdateMessage ->
                    sendMessageOutPort.sendUpdateSessionUserMessage("update-session-user", sessionUserUpdateMessage)
        );
    }

    public SessionConfirmedMessage getSessionConfirmedMessage(String sessionUuid, boolean sessionIsConfirmed) {
        return SessionConfirmedMessage.builder().sessionUuid(sessionUuid).sessionIsConfirmed(sessionIsConfirmed).build();
    }
    public SessionUserUpdateMessage getSessionUserUpdateMessage(String userUuid, LocalDate startDate,
                                                                String sessionUuid, Status status) {
        return SessionUserUpdateMessage.builder()
                .userUuid(userUuid)
                .startDate(startDate)
                .sessionUuid(sessionUuid)
                .status(status).build();
    }



}