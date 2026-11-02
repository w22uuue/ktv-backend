package com.ruoyi.web.controller.ktv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** KTV errors use real HTTP status codes and never expose driver/provider messages. */
@RestControllerAdvice(assignableTypes = {KtvController.class, KtvOperationsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KtvErrors {
    private static final Logger log=LoggerFactory.getLogger(KtvErrors.class);
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception e) {
        int code=500; String message="服务暂不可用，请稍后重试";
        if(e instanceof ResponseStatusException) { code=((ResponseStatusException)e).getRawStatusCode(); message=((ResponseStatusException)e).getReason(); }
        else if(e instanceof com.wechat.pay.java.core.exception.ValidationException) {code=401;message="通知签名无效";}
        else if(e instanceof com.wechat.pay.java.core.exception.MalformedMessageException) {code=400;message="通知格式无效";}
        else if(e instanceof com.wechat.pay.java.core.exception.HttpException || e instanceof com.wechat.pay.java.core.exception.ServiceException || e instanceof org.springframework.web.client.RestClientException) {code=502;message="微信服务暂不可用，请稍后核对订单状态";}
        else if(e instanceof HttpRequestMethodNotSupportedException) { code=405;message="请求方式不支持"; }
        else if(e instanceof DataAccessException) { code=503;message="数据服务暂不可用，请稍后重试"; }
        else if(e instanceof IllegalArgumentException || e instanceof com.alibaba.fastjson2.JSONException || e instanceof HttpMessageNotReadableException || e instanceof MissingServletRequestParameterException || e instanceof MissingServletRequestPartException) { code=400;message="请求数据格式无效或缺少必填参数"; }
        if(code>=500) log.error("KTV request failed ({})",e.getClass().getSimpleName());
        return ResponseEntity.status(code).body(KtvService.object("message",message));
    }
}
