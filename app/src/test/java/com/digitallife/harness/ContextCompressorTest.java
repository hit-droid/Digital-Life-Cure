package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.LLMClient;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ContextCompressorTest {

    private static LLMClient.ChatMessage msg(String role, String text) {
        return new LLMClient.ChatMessage(role, text);
    }

    private static String pad(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    public void underBudgetReturnsSameList() {
        List<LLMClient.ChatMessage> in = new ArrayList<>();
        in.add(msg("user", "短"));
        in.add(msg("assistant", "也短"));
        assertSame(in, ContextCompressor.compact(in));
    }

    @Test
    public void emptyAndNullPassThrough() {
        assertEquals(null, ContextCompressor.compact(null));
        List<LLMClient.ChatMessage> empty = new ArrayList<>();
        assertSame(empty, ContextCompressor.compact(empty));
    }

    @Test
    public void overBudgetKeepsRecentAndInsertsNote() {
        List<LLMClient.ChatMessage> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            in.add(msg("user", pad(400) + i));
        }
        List<LLMClient.ChatMessage> out = ContextCompressor.compact(in, 2000, 4);
        assertTrue(out.size() < in.size());
        assertTrue(out.get(0).content.contains("已省略更早的"));
        assertTrue(out.get(out.size() - 1).content.endsWith("19"));
        int dropped = in.size() - (out.size() - 1);
        assertTrue(dropped >= 1);
    }

    @Test
    public void applyUsesDefaultBudget() {
        ContextCompressor c = new ContextCompressor();
        List<LLMClient.ChatMessage> in = new ArrayList<>();
        in.add(msg("user", "hi"));
        assertEquals(1, c.apply(in).size());
    }
}
