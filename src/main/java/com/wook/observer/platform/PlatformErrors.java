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
    public ResponseEntity<Map<String, String>> problem(ApiProblem problem) {
        OperationLog.failure("api.rejected", problem);
        return ResponseEntity.status(problem.status).body(Map.of("message", problem.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, MaxUploadSizeExceededException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        OperationLog.warn("api.invalid_input", "errorType", exception.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식과 파일 크기를 확인하세요."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        OperationLog.failure("api.failed", exception);
        return ResponseEntity.internalServerError().body(Map.of("message", "처리 중 오류가 발생했습니다. 관리자에게 요청 ID를 전달하세요.",
                "requestId", java.util.Objects.toString(org.slf4j.MDC.get("requestId"), "")));
    }
}
