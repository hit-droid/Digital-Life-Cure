package com.digitallife.harness;

public interface Plugin {
    String id();

    void activate(HarnessContext ctx);
}
