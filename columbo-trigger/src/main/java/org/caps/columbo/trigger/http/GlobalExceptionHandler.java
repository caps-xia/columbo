package org.caps.columbo.trigger.http;

import lombok.extern.slf4j.Slf4j;
import org.caps.columbo.api.response.Response;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：Controller 不再写 try-catch，所有异常统一在这里转成 Response返回。
 * 客户端问题（参数/格式/路径/方法）映射 0002/0003，服务端问题兜底 0001，堆栈只进日志不外露。
 * 流式接口（ResponseBodyEmitter）开流后的异常仍由其自身 completeWithError 处理，不走这里。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按异常携带的 code/info 返回（预期内的业务拒绝，warn 级即可，避免 error 告警噪音） */
    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(AppException e) {
        log.warn("业务异常 code:{} info:{}", e.getCode(), e.getInfo());
        return Response.error(e.getCode(), e.getInfo());
    }

    /** 未登录/token 失效：Sa-Token 拦截器抛出，转业务码 0301（前端据此跳登录页） */
    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public Response<Void> handleNotLoginException(cn.dev33.satoken.exception.NotLoginException e) {
        log.info("未登录访问：{}", e.getMessage());
        return Response.error(ResponseCode.NOT_LOGIN.getCode(), ResponseCode.NOT_LOGIN.getInfo());
    }

    /** 参数校验异常：@Valid 校验失败时，返回具体的字段错误提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 {}", message);
        return Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
    }

    /** 请求体不可读：JSON 格式错误、字段类型不匹配等 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Response<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 {}", e.getMessage());
        return Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请求体格式错误，请检查 JSON 结构与字段类型");
    }

    /** 缺少必填的 query/form 参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Response<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数 {}", e.getParameterName());
        return Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), "缺少必填参数：" + e.getParameterName());
    }

    /** 参数类型不匹配（如要求数字传了字符串） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Response<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误 {}", e.getName());
        return Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), "参数类型错误：" + e.getName());
    }

    /** 请求路径不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Response<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("路径不存在 {}", e.getResourcePath());
        return Response.error(ResponseCode.NOT_FOUND_METHOD.getCode(), "接口不存在");
    }

    /** 请求方式不支持（如 GET 打了 POST 接口） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Response<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方式不支持 {}", e.getMethod());
        return Response.error(ResponseCode.NOT_FOUND_METHOD.getCode(), "请求方式不支持：" + e.getMethod());
    }

    /**
     * SSE/异步请求超时（ResponseBodyEmitter 到时）：此时连接已断、响应已提交，任何返回体都写不进
     * event-stream（返回 Response 会触发 HttpMessageNotWritableException 二次异常），只记日志静默收尾。
     * 上游 LLM 订阅的取消由各 Controller 的 emitter.onTimeout 负责。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeout(org.springframework.web.context.request.async.AsyncRequestTimeoutException e) {
        log.warn("异步请求（SSE）超时，连接已收尾：{}", e.getMessage());
    }

    /**
     * SSE 客户端已断开（用户点停止/关页面/刷新触发 fetch abort，后端再 send 即 Broken pipe）：
     * 属用户正常操作而非服务异常，一行 warn 不带堆栈，且必须返回 void——此时响应已按
     * text/event-stream 提交，返回 Response 会触发 HttpMessageNotWritableException 二次噪音
     * （2026-10-24 线上：点一次停止打两条 ERROR + 300 行堆栈）。上游 LLM 订阅的取消
     * 由各 Controller 的 emitter.onError/onCompletion 负责，这里无需也不能再收尾。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.warn("SSE 客户端已断开（停止/关闭页面），丢弃后续事件：{}", e.getMessage());
    }

    /** 兜底异常：具体堆栈只进日志，对外只给通用提示 */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Response.error(ResponseCode.UN_ERROR);
    }

}
