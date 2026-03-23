package com.onyx.android.sdk.hwr.service;

import android.os.ParcelFileDescriptor;
import com.onyx.android.sdk.hwr.service.HWROutputCallback;
import com.onyx.android.sdk.hwr.service.HWRInputArgs;
import com.onyx.android.sdk.hwr.service.HWRCommandArgs;

// Method order determines transaction codes — must match the service exactly.
// init=1, compileRecognizeText=2, batchRecognize=3, openIncrementalRecognizer=4,
// execCommand=5, closeRecognizer=6
// All methods are oneway (async) to match the service's original interface.
oneway interface IHWRService {
    void init(in HWRInputArgs args, boolean forceReinit, HWROutputCallback callback);
    void compileRecognizeText(String text, String language, HWROutputCallback callback);
    void batchRecognize(in ParcelFileDescriptor pfd, HWROutputCallback callback);
    void openIncrementalRecognizer(in HWRInputArgs args, HWROutputCallback callback);
    void execCommand(in HWRInputArgs args, in HWRCommandArgs cmdArgs, HWROutputCallback callback);
    void closeRecognizer();
}
