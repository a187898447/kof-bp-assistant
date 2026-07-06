package com.kof.bpassistant.analysis

import android.os.Parcel
import android.os.Parcelable
import com.kof.bpassistant.data.CounterStrategy

/**
 * Parcelable 包装版本的 AnalysisResult，用于通过 Intent 传递给 FloatingWindowService。
 */
data class AnalysisResult(
    val banIntentText: String,
    val comboMatch: ComboMatchResult?,
    val counterStrategy: CounterStrategy?,
    val heroCounters: List<HeroCounterResult>,
    val seasonNote: String?,
    val analysisMs: Long
) : Parcelable {

    constructor(parcel: Parcel) : this(
        banIntentText = parcel.readString() ?: "",
        comboMatch = parcel.readParcelable(ComboMatchResult::class.java.classLoader),
        counterStrategy = parcel.readParcelable(CounterStrategy::class.java.classLoader),
        heroCounters = mutableListOf<HeroCounterResult>().also {
            parcel.readList(it, HeroCounterResult::class.java.classLoader)
        },
        seasonNote = parcel.readString(),
        analysisMs = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(banIntentText)
        parcel.writeParcelable(comboMatch, flags)
        parcel.writeParcelable(counterStrategy, flags)
        parcel.writeList(heroCounters)
        parcel.writeString(seasonNote)
        parcel.writeLong(analysisMs)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AnalysisResult> {
        override fun createFromParcel(parcel: Parcel): AnalysisResult = AnalysisResult(parcel)
        override fun newArray(size: Int): Array<AnalysisResult?> = arrayOfNulls(size)
    }
}

data class ComboMatchResult(
    val comboName: String,
    val comboTag: String,
    val confidence: String,
    val score: Double
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readDouble()
    )
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(comboName)
        parcel.writeString(comboTag)
        parcel.writeString(confidence)
        parcel.writeDouble(score)
    }
    override fun describeContents(): Int = 0
    companion object CREATOR : Parcelable.Creator<ComboMatchResult> {
        override fun createFromParcel(parcel: Parcel): ComboMatchResult = ComboMatchResult(parcel)
        override fun newArray(size: Int): Array<ComboMatchResult?> = arrayOfNulls(size)
    }
}

data class HeroCounterResult(
    val heroId: String,
    val heroName: String,
    val top3Counters: List<CounterHero>,
    val note: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        mutableListOf<CounterHero>().also { parcel.readList(it, CounterHero::class.java.classLoader) },
        parcel.readString() ?: ""
    )
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(heroId)
        parcel.writeString(heroName)
        parcel.writeList(top3Counters)
        parcel.writeString(note)
    }
    override fun describeContents(): Int = 0
    companion object CREATOR : Parcelable.Creator<HeroCounterResult> {
        override fun createFromParcel(parcel: Parcel): HeroCounterResult = HeroCounterResult(parcel)
        override fun newArray(size: Int): Array<HeroCounterResult?> = arrayOfNulls(size)
    }
}

data class CounterHero(
    val heroId: String,
    val heroName: String,
    val counterType: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(heroId)
        parcel.writeString(heroName)
        parcel.writeString(counterType)
    }
    override fun describeContents(): Int = 0
    companion object CREATOR : Parcelable.Creator<CounterHero> {
        override fun createFromParcel(parcel: Parcel): CounterHero = CounterHero(parcel)
        override fun newArray(size: Int): Array<CounterHero?> = arrayOfNulls(size)
    }
}
