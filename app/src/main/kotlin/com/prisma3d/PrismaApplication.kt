package com.prisma3d

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.rx3.RxDataStore
import androidx.datastore.preferences.rx3.RxPreferenceDataStoreBuilder
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.hannesdorfmann.processphoenix.ProcessPhoenix
import dagger.hilt.android.HiltAndroidApp
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class PrismaApplication : Application() {

    // Coroutine Scope tied to Application Lifecycle
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // DataStore for Settings
    lateinit var settingsDataStore: RxDataStore<Preferences>

    // Room Database Instance
    lateinit var sceneDatabase: SceneDatabase

    // Custom Font
    lateinit var customTypeface: Typeface

    // Filament Engine Initialization Flag
    private var isEngineInitialized = false

    override fun onCreate() {
        super.onCreate()

        // 1. ProcessPhoenix: Handle crash recovery (must be early)
        if (ProcessPhoenix.onCreate(this)) {
            return // Restarted by ProcessPhoenix, skip initialization
        }

        // 2. Timber Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release: Crashlytics Tree or custom remote logging tree
            Timber.plant(CrashlyticsTree())
        }
        Timber.tag("Prisma3D")

        // 3. Disable Auto Dark Mode for consistent UI (optional but common for 3D apps)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // 4. Initialize DataStore (Settings)
        settingsDataStore = RxPreferenceDataStoreBuilder(this, "settings_datastore").build()

        // 5. Initialize Room Database (SceneDB)
        sceneDatabase = Room.databaseBuilder(
            applicationContext,
            SceneDatabase::class.java, "scene_database"
        )
            .fallbackToDestructiveMigration() // Adjust migration strategy as needed
            .build()

        // 6. Load Custom UI Font
        customTypeface = Typeface.createFromAsset(assets, "fonts/prisma_ui_font.ttf") // Adjust path/name

        // 7. Initialize Crash Reporting (Firebase Crashlytics)
        // Note: Usually auto-initialized via Gradle plugin, but explicit init ensures context.
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        // 8. Initialize Filament/Prisma Engine
        // Defer heavy native init slightly or run on background if PrismaEngine.create blocks.
        // Assuming PrismaEngine.create(context) is safe to call on main thread or handles threading internally.
        initEngine()

        // 9. Lifecycle Observer for App Foreground/Background (Optional but good for Engine pause/resume)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(this))
    }

    private fun initEngine() {
        if (isEngineInitialized) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                // PrismaEngine.create(this@PrismaApplication) // Pass Application Context
                // Simulating the call:
                PrismaEngine.create(this@PrismaApplication)
                isEngineInitialized = true
                Timber.i("PrismaEngine initialized successfully.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize PrismaEngine")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            // Notify Engine to release GPU resources/caches if applicable
            PrismaEngine.onTrimMemory(level)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        // Clean up Engine if needed
        PrismaEngine.destroy()
    }

    companion object {
        fun get(context: Context): PrismaApplication = context.applicationContext as PrismaApplication
    }
}

// Helper: Timber Tree for Crashlytics
class CrashlyticsTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.VERBOSE || priority == Log.DEBUG || priority == Log.INFO) return

        FirebaseCrashlytics.getInstance().recordException(t ?: Exception(message)).addOnFailureListener {
            // Fallback logging if Crashlytics fails
            android.util.Log.e("CrashlyticsTree", "Failed to record exception", it)
        }
    }
}

// Helper: Lifecycle Observer for App State
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class AppLifecycleObserver(private val app: PrismaApplication) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // App entered foreground
        app.applicationScope.launch { PrismaEngine.onAppForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // App entered background
        app.applicationScope.launch { PrismaEngine.onAppBackground() }
    }
}

// Placeholder classes/interfaces to make code compile (User expects these to exist in project)
interface SceneDatabase
class PrismaEngine {
    companion object {
        @JvmStatic fun create(context: PrismaApplication) { /* Native Init */ }
        @JvmStatic fun destroy() { /* Native Cleanup */ }
        @JvmStatic fun onTrimMemory(level: Int) { /* Release GPU */ }
        @JvmStatic fun onAppForeground() { /* Resume Rendering */ }
        @JvmStatic fun onAppBackground() { /* Pause Rendering */ }
    }
}