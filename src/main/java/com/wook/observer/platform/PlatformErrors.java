package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = PlatformController.class)
@Profile("!collector")
public class PlatformErrors {
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, String>> problem(ApiProblem problem) { return ResponseEntity.status(problem.status).body(Map.of("message", problem.getMessage())); }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) { return ResponseEntity.badRequest().body(Map.of("message", "입력 형식과 파일 크기를 확인하세요.")); }
}
