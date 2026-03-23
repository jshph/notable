package com.onyx.android.sdk.hwr.service

import android.os.Parcel
import android.os.Parcelable

/** Stub parcelable required by AIDL — not used in batchRecognize path. */
class HWRCommandArgs() : Parcelable {
    constructor(parcel: Parcel) : this()

    override fun writeToParcel(parcel: Parcel, flags: Int) {}
    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HWRCommandArgs> {
        override fun createFromParcel(parcel: Parcel) = HWRCommandArgs(parcel)
        override fun newArray(size: Int) = arrayOfNulls<HWRCommandArgs>(size)
    }
}
