package com.multitab.sessionRequest.adaptor.out.feignClient;

import com.multitab.sessionRequest.adaptor.out.feignClient.dto.SessionResponseOutDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(name="mentoring-service")
public interface MentoringServiceFeignClient {

    // mentoring-service 의 세션 조회 api 호출
    @GetMapping("/api/v1/mentoring-service/session/{uuid}")
    SessionResponseOutDto getSession(@PathVariable(name = "uuid") String uuid);

    @PutMapping("/api/v1/mentoring-service/session/{uuid}")
    void closeSession(@PathVariable(name = "uuid") String uuid);
}
