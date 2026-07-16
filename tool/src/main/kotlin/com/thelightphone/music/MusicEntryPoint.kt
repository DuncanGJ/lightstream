package com.thelightphone.music

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Tool entry point. App singletons ([com.thelightphone.music.app.MusicApp]) are initialised lazily
 * from the first screen (which has a [com.thelightphone.sdk.SealedLightContext]), so there is
 * nothing to do here yet — but exactly one @EntryPoint object is required by the SDK.
 */
@EntryPoint
object MusicEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit
}
