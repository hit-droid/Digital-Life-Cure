package com.digitallife.brain;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.RejectedExecutionException;

/**
 * {@link LLMClient} 单测（v1.191.0）。
 *
 * <p>每个 {@code LLMClient} 实例都持有一个常驻的单线程池。凡是「用完即弃」地新建客户端
 * 的地方（定时任务、记忆提取、主动话术升级…），不调 {@code close()} 就会每次泄漏一个线程。
 * 这里锁住「close 之后不再接受新任务」这个契约，让调用方漏调时能立刻暴露。</p>
 */
public class LLMClientTest {

    @Test
    public void close_rejectsFurtherWork() {
        LLMClient llm = new LLMClient("http://127.0.0.1:1", "k", "m");
        assertNotNull(llm);
        llm.close();
        try {
            llm.chatStream(new java.util.ArrayList<>(), null, new LLMClient.StreamListener() {
                @Override
                public void onDelta(String delta) {}

                @Override
                public void onToolCall(String name, org.json.JSONObject args, String callId) {}

                @Override
                public void onDone(String fullText) {}

                @Override
                public void onError(String error) {}
            });
            fail("close 之后不应再接受新任务");
        } catch (RejectedExecutionException expected) {
            // 预期：线程池已关闭
        }
    }

    @Test
    public void close_isIdempotent() {
        LLMClient llm = new LLMClient("http://127.0.0.1:1", "k", "m");
        llm.close();
        llm.close();
        llm.cancel();
        assertTrue(true);
    }
}
