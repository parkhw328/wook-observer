package com.wook.observer.platform;

public class ApiProblem extends RuntimeException {
    public final int status;
    public ApiProblem(int status, String message) { super(message); this.status = status; }
}
