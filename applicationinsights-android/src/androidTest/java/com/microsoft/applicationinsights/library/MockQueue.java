package com.microsoft.applicationinsights.library;

import com.microsoft.applicationinsights.library.config.ApplicationInsightsConfig;

import java.util.concurrent.CountDownLatch;

public class MockQueue extends ChannelQueue {

    public int responseCode;
    public CountDownLatch sendSignal;
    public CountDownLatch responseSignal;
    public MockSender sender;

    public MockQueue(int expectedSendCount) {
        super(new ApplicationInsightsConfig());
        this.responseCode = 0;
        this.sendSignal = new CountDownLatch(expectedSendCount);
        this.responseSignal = new CountDownLatch(expectedSendCount);
    }

    public long getQueueSize() {
        return this.list.size();
    }
}