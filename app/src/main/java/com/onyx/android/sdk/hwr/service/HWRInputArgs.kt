package com.onyx.android.sdk.hwr.service

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Parcelable matching the ksync service's HWRInputArgs.
 * Field order: inputData (Parcelable), pfd (Parcelable), content (String).
 *
 * inputData is written as a Parcelable with class name
 * "com.onyx.android.sdk.hwr.bean.HWRInputData" so the service's classloader
 * can deserialize it. We manually write the same fields.
 */
class HWRInputArgs() : Parcelable {
    // HWRInputData fields
    var lang: String = "en_US"
    var contentType: String = "Text"
    var recognizerType: String = "Text"
    var viewWidth: Float = 0f
    var viewHeight: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var isGestureEnable: Boolean = false
    var isTextEnable: Boolean = true
    var isShapeEnable: Boolean = false
    var isIncremental: Boolean = false

    // HWRInputArgs fields
    var pfd: ParcelFileDescriptor? = null
    var content: String? = null

    constructor(parcel: Parcel) : this() {
        // Read inputData as Parcelable (skip class name + fields)
        val className = parcel.readString()
        if (className != null) {
            lang = parcel.readString() ?: "en_US"
            contentType = parcel.readString() ?: "Text"
            recognizerType = parcel.readString() ?: "Text"
            viewWidth = parcel.readFloat()
            viewHeight = parcel.readFloat()
            offsetX = parcel.readFloat()
            offsetY = parcel.readFloat()
            isGestureEnable = parcel.readByte() != 0.toByte()
            isTextEnable = parcel.readByte() != 0.toByte()
            isShapeEnable = parcel.readByte() != 0.toByte()
            isIncremental = parcel.readByte() != 0.toByte()
        }
        pfd = parcel.readParcelable(ParcelFileDescriptor::class.java.classLoader)
        content = parcel.readString()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        // Write inputData as a Parcelable: class name then fields
        // This matches Parcel.writeParcelable format
        parcel.writeString("com.onyx.android.sdk.hwr.bean.HWRInputData")
        parcel.writeString(lang)
        parcel.writeString(contentType)
        parcel.writeString(recognizerType)
        parcel.writeFloat(viewWidth)
        parcel.writeFloat(viewHeight)
        parcel.writeFloat(offsetX)
        parcel.writeFloat(offsetY)
        parcel.writeByte(if (isGestureEnable) 1 else 0)
        parcel.writeByte(if (isTextEnable) 1 else 0)
        parcel.writeByte(if (isShapeEnable) 1 else 0)
        parcel.writeByte(if (isIncremental) 1 else 0)

        // Write pfd as Parcelable
        if (pfd != null) {
            parcel.writeString("android.os.ParcelFileDescriptor")
            pfd!!.writeToParcel(parcel, flags)
        } else {
            parcel.writeString(null)
        }

        // Write content
        parcel.writeString(content)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HWRInputArgs> {
        override fun createFromParcel(parcel: Parcel) = HWRInputArgs(parcel)
        override fun newArray(size: Int) = arrayOfNulls<HWRInputArgs>(size)
    }
}
