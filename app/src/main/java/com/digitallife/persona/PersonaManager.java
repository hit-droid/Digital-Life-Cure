package com.digitallife.persona;

import android.content.Context;

import java.util.List;

/**
 * 角色管理器（v1.24.0）。
 * 包装 PersonaStore，提供全局访问入口。
 * 同时管理"当前活跃角色"的状态广播。
 */
public class PersonaManager {

    public interface OnPersonaChangeListener {
        void onPersonaChanged(Persona newPersona);
    }

    private static volatile PersonaManager INSTANCE;
    private final Context appCtx;
    private OnPersonaChangeListener listener;

    public static PersonaManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (PersonaManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PersonaManager(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private PersonaManager(Context ctx) {
        this.appCtx = ctx;
    }

    public PersonaStore store() {
        return PersonaStore.get(appCtx);
    }

    public Persona active() {
        return store().active();
    }

    public List<Persona> all() {
        return store().all();
    }

    public void switchTo(String id) {
        Persona old = store().active();
        store().setActive(id);
        Persona neu = store().active();
        if (listener != null && neu != null
                && (old == null || !old.id.equals(neu.id))) {
            listener.onPersonaChanged(neu);
        }
    }

    public void setOnPersonaChangeListener(OnPersonaChangeListener l) {
        this.listener = l;
    }

    public OnPersonaChangeListener getOnPersonaChangeListener() {
        return listener;
    }
}
