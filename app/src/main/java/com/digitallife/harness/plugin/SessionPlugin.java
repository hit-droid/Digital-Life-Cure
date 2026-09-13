package com.digitallife.harness.plugin;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.SessionLog;

public final class SessionPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-session";
    }

    @Override
    public void activate(HarnessContext ctx) {
        SessionLog log = new SessionLog();
        log.attach(ctx.events());
        ctx.provide("sessions", log);
    }
}
