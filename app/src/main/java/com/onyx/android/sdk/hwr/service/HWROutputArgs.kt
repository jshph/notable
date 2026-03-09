package com.onyx.android.sdk.hwr.service

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Parcelable matching the KHwrService output format.
 * Field order must match the service's writeToParcel exactly:
 * pfd, recognizerActivated, compileSuccess, hwrResult, gesture(null), outputType, itemIdMap
 */
class HWROutputArgs() : Parcelable {
    var pfd: ParcelFileDescriptor? = null
    var recognizerActivated: Boolean = false
    var compileSuccess: Boolean = false
    var hwrResult: String? = null
    var gesture: String? = null
    var outputType: Int = 0
    var itemIdMap: String? = null

    constructor(parcel: Parcel) : this() {
        pfd = parcel.readParcelable(ParcelFileDescriptor::class.java.classLoader)
        recognizerActivated = parcel.readByte() != 0.toByte()
        compileSuccess = parcel.readByte() != 0.toByte()
        hwrResult = parcel.readString()
        gesture = parcel.readString()
        outputType = parcel.readInt()
        itemIdMap = parcel.readString()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(pfd, flags)
        parcel.writeByte(if (recognizerActivated) 1 else 0)
        parcel.writeByte(if (compileSuccess) 1 else 0)
        parcel.writeString(hwrResult)
        parcel.writeString(gesture)
        parcel.writeInt(outputType)
        parcel.writeString(itemIdMap)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HWROutputArgs> {
        override fun createFromParcel(parcel: Parcel) = HWROutputArgs(parcel)
        override fun newArray(size: Int) = arrayOfNulls<HWROutputArgs>(size)
    }
}
