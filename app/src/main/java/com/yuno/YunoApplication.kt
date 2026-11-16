package com.yuno

import android.app.ActivityManager
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe
import kotlin.concurrent.thread

class YunoApplication : Application(), ImageLoaderFactory {

    /** The Shorts feed, players and bank: one per process, outliving any window. */
    val shorts by lazy { ShortsState(this, kotlinx.coroutines.MainScope()) }

    override fun onCreate() {
        super.onCreate()
        // Before anything that could crash, so its stack trace is kept (Settings › About › Crash report).
        CrashLog.install(this)
        // First: everything below may already word something (str()).
        AppLanguage.init(this)
        NewPipe.init(NewPipeDownloader.instance)
        // The iOS client only adds HLS livestream formats; Yuno plays VOD, so skip that request.
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false)
        ThemeSettings.init(this)
        LiquidGlassSetting.init(this)
        Onboarding.init(this)
        QualityPreference.init(this)
        PlaybackSpeed.init(this)
        AudioOnly.init(this)
        PlayerControls.init(this)
        FullscreenZoom.init(this)
        SeekStep.init(this)
        ChaptersStayOpen.init(this)
        AutoPip.init(this)
        CaptionSettings.init(this)
        CaptionTranslator.refresh()
        SearchHistory.init(this)
        ResumeStore.init(this)
        SavedSession.init(this)
        Account.init(this)
        Bells.init(this)
        // WorkManager's first use opens its database; nothing on screen waits for the schedule,
        // so it leaves the main thread (after Bells.init, which it may read).
        thread(name = "yuno-schedule") { NewVideos.schedule(this) }
        SponsorBlock.init(this)
        ShortsSetting.init(this)
        ShortsBank.init(this)
        AppUpdate.init(this)
        // Stream URLs are bound to the network they were fetched on: forget them on a switch.
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                private var last: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (last != null && last != network) StreamCache.invalidateAll()
                    last = network
                }
            },
        )
    }

    // Thumbnails are in every list and the player; a short crossfade replaces pop-in.
    // The cache is a quarter of the NORMAL memory class: with largeHeap on, Coil's own percentage
    // is taken of the (much bigger) large class and the cache grows to well over 100 MB.
    override fun newImageLoader(): ImageLoader {
        val memoryClassMb = getSystemService(ActivityManager::class.java).memoryClass
        val cacheBytes = (memoryClassMb * 1024L * 1024L / 4).toInt()
        return ImageLoader.Builder(this)
            .crossfade(180)
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(cacheBytes).build() }
            .build()
    }
}
