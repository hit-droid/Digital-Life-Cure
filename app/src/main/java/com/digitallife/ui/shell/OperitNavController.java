package com.digitallife.ui.shell;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.digitallife.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Operit 路由控制器（v1.23.0 仿 Operit AI NavController）。
 * - 维护当前 route
 * - 切换时把 OperitContentView.create(route) 放入 content container
 * - 提供 route 列表的变更回调（侧栏选中态同步）
 */
public class OperitNavController {

    public interface OnRouteChangeListener {
        void onRouteChanged(OperitRoute old, OperitRoute newRoute);
    }

    private final Context ctx;
    private final FrameLayout content;
    private final OperitContentView.Host host;

    private OperitRoute current = OperitRoute.CHAT;
    private View currentView;
    private final List<OnRouteChangeListener> listeners = new ArrayList<>();

    public OperitNavController(Context ctx, FrameLayout content, OperitContentView.Host host) {
        this.ctx = ctx;
        this.content = content;
        this.host = host;
    }

    public void addListener(OnRouteChangeListener l) { listeners.add(l); }
    public void removeListener(OnRouteChangeListener l) { listeners.remove(l); }
    public OperitRoute current() { return current; }

    public void navigate(OperitRoute route) {
        if (route == current && currentView != null) return;
        OperitRoute old = current;
        current = route;
        View v = OperitContentView.create(route, host);
        if (v == null) return;
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        currentView = v;
        for (OnRouteChangeListener l : listeners) l.onRouteChanged(old, route);
    }
}
