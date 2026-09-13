package com.digitallife.harness;

public interface Disposable {
    void dispose();

    Disposable NONE = () -> { };
}
