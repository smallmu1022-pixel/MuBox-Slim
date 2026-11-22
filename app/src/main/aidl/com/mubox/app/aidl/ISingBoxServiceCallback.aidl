package com.mubox.app.aidl;

oneway interface ISingBoxServiceCallback {
    void onStateChanged(
        int state,
        String activeLabel,
        String lastError,
        boolean manuallyStopped,
        long generation
    );
    void onUrlTestNodeDelayResult(long requestId, int delay);
    void onLatencyTestResult(long requestId, String tag, long latency);
    void onLatencyTestFinished(long requestId);
}
