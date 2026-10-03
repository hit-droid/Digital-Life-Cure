package com.digitallife.ui.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FailoverPolicy} 的分支穷举：哪些错误值得切备用模型、哪些不值得。
 * 这套判定直接决定"模型挂掉后用户能不能自动恢复"，此前没有测试。
 */
public class FailoverPolicyTest {

    @Test
    public void nullIsNotFailoverable() {
        assertFalse(FailoverPolicy.isFailoverable(null));
    }

    @Test
    public void authRateLimitAndConflictCodesAreFailoverable() {
        assertTrue(FailoverPolicy.isFailoverable("HTTP 401 Unauthorized"));
        assertTrue(FailoverPolicy.isFailoverable("HTTP 403 Forbidden"));
        assertTrue(FailoverPolicy.isFailoverable("HTTP 408 Timeout"));
        assertTrue(FailoverPolicy.isFailoverable("HTTP 409 Conflict"));
        assertTrue(FailoverPolicy.isFailoverable("HTTP 429 Too Many Requests"));
    }

    @Test
    public void other4xxAreNotFailoverable() {
        assertFalse("400 是请求本身有问题，换模型也没用",
                FailoverPolicy.isFailoverable("HTTP 400 Bad Request"));
        assertFalse(FailoverPolicy.isFailoverable("HTTP 404 Not Found"));
        assertFalse(FailoverPolicy.isFailoverable("HTTP 422 Unprocessable Entity"));
    }

    @Test
    public void bareHttp4WithoutCodeIsNotFailoverable() {
        assertFalse(FailoverPolicy.isFailoverable("HTTP 4xx"));
    }

    @Test
    public void serverErrorsAreFailoverable() {
        assertTrue(FailoverPolicy.isFailoverable("HTTP 500 Internal Server Error"));
        assertTrue(FailoverPolicy.isFailoverable("HTTP 503 Service Unavailable"));
    }

    @Test
    public void networkErrorsWithoutHttpCodeAreFailoverable() {
        assertTrue(FailoverPolicy.isFailoverable("Connection refused"));
        assertTrue(FailoverPolicy.isFailoverable("timeout"));
        assertTrue(FailoverPolicy.isFailoverable("UnknownHostException: api.example.com"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertTrue(FailoverPolicy.isFailoverable("http 429 too many requests"));
        assertFalse(FailoverPolicy.isFailoverable("http 400 bad request"));
    }
}
