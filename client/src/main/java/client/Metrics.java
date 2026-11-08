package client;

import java.util.concurrent.atomic.AtomicInteger;

public class Metrics {
    public final AtomicInteger successful = new AtomicInteger();
    public final AtomicInteger failed = new AtomicInteger();
    public final AtomicInteger retries = new AtomicInteger();
    public final AtomicInteger connectionErrors = new AtomicInteger();
}