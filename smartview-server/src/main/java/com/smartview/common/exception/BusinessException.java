package com.smartview.common.exception;

import com.smartview.common.api.ResponseCode;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final ResponseCode responseCode;
    private final HttpStatus httpStatus;

    public BusinessException(String message) {
        this(ResponseCode.BUSINESS_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(ResponseCode responseCode, String message) {
        this(responseCode, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(ResponseCode responseCode, String message, HttpStatus httpStatus) {
        super(message);
        this.responseCode = responseCode;
        this.httpStatus = httpStatus;
    }

    public ResponseCode getResponseCode() {
        return responseCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
