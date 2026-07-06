package com.kof.bpassistant.data

import android.os.Parcel
import android.os.Parcelable

// 阵容级反制策略（Parcelable，通过 Intent 传递）
data class CounterStrategy(
    val comboTag: String,
    val threatPoints: List<String>,
    val counterThought: String,
    val recommendedTags: List<String>,
    val avoidTypes: List<String>
) : Parcelable {

    constructor(parcel: Parcel) : this(
        comboTag = parcel.readString() ?: "",
        threatPoints = parcel.createStringArrayList() ?: emptyList(),
        counterThought = parcel.readString() ?: "",
        recommendedTags = parcel.createStringArrayList() ?: emptyList(),
        avoidTypes = parcel.createStringArrayList() ?: emptyList()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(comboTag)
        parcel.writeStringList(threatPoints)
        parcel.writeString(counterThought)
        parcel.writeStringList(recommendedTags)
        parcel.writeStringList(avoidTypes)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CounterStrategy> {
        override fun createFromParcel(parcel: Parcel): CounterStrategy = CounterStrategy(parcel)
        override fun newArray(size: Int): Array<CounterStrategy?> = arrayOfNulls(size)
    }
}
