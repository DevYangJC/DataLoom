package com.demo.excel.common;

/**
 * 统一返回体
 */
public class ApiResponse<T> {

    private int code;
    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.success = true;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        ApiResponse<T> r = ok(data);
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 500;
        r.success = false;
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> r = fail(message);
        r.code = code;
        return r;
    }

    // getter / setter
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
