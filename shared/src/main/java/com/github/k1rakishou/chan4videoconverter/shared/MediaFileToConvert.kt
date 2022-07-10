package com.github.k1rakishou.chan4videoconverter.shared

import android.os.Parcel
import android.os.Parcelable


const val CONVERT_MEDIA_ACTION = "com.github.k1rakishou.chan4videoconverter.CONVERT_MEDIA_ACTION"
const val MEDIA_FILES_TO_CONVERT = "media_files_to_convert"

data class MediaFileToConvert(
  val inputFilePath: String,
  val outputFilePath: String,
  val canUseSound: Boolean,
) : Parcelable {
  constructor(parcel: Parcel) : this(
    parcel.readString()!!,
    parcel.readString()!!,
    parcel.readByte() != 0.toByte()
  )

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    parcel.writeString(inputFilePath)
    parcel.writeString(outputFilePath)
    parcel.writeByte(if (canUseSound) 1 else 0)
  }

  override fun describeContents(): Int {
    return 0
  }

  companion object CREATOR : Parcelable.Creator<MediaFileToConvert> {
    override fun createFromParcel(parcel: Parcel): MediaFileToConvert {
      return MediaFileToConvert(parcel)
    }

    override fun newArray(size: Int): Array<MediaFileToConvert?> {
      return arrayOfNulls(size)
    }
  }
}