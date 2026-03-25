package com.onyx.android.sdk.hwr.service;

import com.onyx.android.sdk.hwr.service.HWROutputArgs;

interface HWROutputCallback {
    void read(in HWROutputArgs args);
}
