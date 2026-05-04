package com.arflix.tv.cloudstream

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Play-flavor no-op init. Referenced from [com.arflix.tv.ArflixApplication] on
 * startup; the sideload flavor's counterpart wires arvio's OkHttpClient into
 * the CloudStream runtime, but play builds have no runtime to wire, so this
 * is an empty body.
 */
@Suppress("UNUSED_PARAMETER")
fun initCloudstream(client: OkHttpClient, context: Context? = null) = Unit
