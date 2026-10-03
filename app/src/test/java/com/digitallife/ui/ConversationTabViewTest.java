package com.digitallife.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.digitallife.util.ChatStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ConversationTabView#hasUserSession} 的回归（AGENTS.md 5.4 第 4 条）。
 *
 * 背景：会话列表的空态判定原先是 {@code sessions.isEmpty()}，但 refresh() 开头
 * 先 ensure 了内置护理会话，列表至少有一条 —— 判定恒为 false，空态一次都没显示过。
 * 改成「除内置护理会话外还有没有别的会话」之后，这里把它钉住：
 * 只有护理会话时必须算「空」（要出引导），混进任一条自建会话就不再算空。
 *
 * 只测这个静态方法，不构造 View：类的加载需要 Android 环境，故用 Robolectric，
 * 但断言本身完全是纯逻辑。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ConversationTabViewTest {

    private static ChatStore.SessionInfo session(String id) {
        return new ChatStore.SessionInfo(id, "标题", ChatStore.TYPE_CHAT,
                "chat", null, 0L, 0L);
    }

    private static List<ChatStore.SessionInfo> listOf(ChatStore.SessionInfo... items) {
        List<ChatStore.SessionInfo> out = new ArrayList<>();
        for (ChatStore.SessionInfo s : items) out.add(s);
        return out;
    }

    // ==================== 边界 ====================

    @Test
    public void hasUserSession_nullIsNotUserSession() {
        assertFalse(ConversationTabView.hasUserSession(null));
    }

    @Test
    public void hasUserSession_emptyListIsNotUserSession() {
        assertFalse(ConversationTabView.hasUserSession(new ArrayList<ChatStore.SessionInfo>()));
    }

    @Test
    public void hasUserSession_nullElementIsSkipped() {
        List<ChatStore.SessionInfo> l = new ArrayList<>();
        l.add(null);
        assertFalse(ConversationTabView.hasUserSession(l));
    }

    // ==================== 核心判定 ====================

    @Test
    public void hasUserSession_onlyBuiltInCareIsFalse() {
        // 这是空态要出现的场景：装完 app 还没建过会话，列表里只有内置护理会话
        assertFalse(ConversationTabView.hasUserSession(
                listOf(session(ChatStore.SESSION_CARE))));
    }

    @Test
    public void hasUserSession_carePlusOwnIsTrue() {
        assertTrue(ConversationTabView.hasUserSession(
                listOf(session(ChatStore.SESSION_CARE), session("my-1"))));
    }

    @Test
    public void hasUserSession_onlyOwnIsTrue() {
        // 理论上不会出现（护理会话恒定存在），但判定本身不该依赖这个前提
        assertTrue(ConversationTabView.hasUserSession(listOf(session("my-1"))));
    }
}
