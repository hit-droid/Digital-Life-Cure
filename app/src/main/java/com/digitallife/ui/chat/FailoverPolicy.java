package com.digitallife.ui.chat;

import java.util.Locale;

/**
 * 模型降级判定：判断某条错误是否值得切换到同 scope 的备用模型。
 *
 * <p>规则：HTTP 4xx 里只有 401/403/408/409/429 值得换（鉴权、限流、冲突、超时），
 * 其余 4xx 多为请求本身有问题，换了也没用；HTTP 5xx 与无 HTTP 码的网络错误
 * （连接失败、DNS、超时）都值得换。抽成纯函数以便穷举这些分支。
 */
public final class FailoverPolicy {

    private FailoverPolicy() {
    }

    public static boolean isFailoverable(String error) {
        if (error == null) return false;
        String e = error.toLowerCase(Locale.ROOT);
        if (e.contains("http 4")) {
            return e.contains("401") || e.contains("403") || e.contains("408")
                    || e.contains("409") || e.contains("429");
        }
        // HTTP 5xx 或无 HTTP 码的网络错误都值得切换
        return true;
    }
}
