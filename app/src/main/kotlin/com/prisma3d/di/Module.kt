package com.prisma3d.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesKey
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.google.common.util.concurrent.ListenableFuture
import com.prisma3d.asset.AssetManager
import com.prisma3d.asset.AssetManagerImpl
import com.prisma3d.command.UndoRedoManager
import com.prisma3d.command.UndoRedoManagerImpl
import com.prisma3d.data.SceneDatabase
import com.prisma3d.data.SceneRepository
import com.prisma3d.data.SceneRepositoryImpl
import com.prisma3d.engine.PrismaEngine
import com.prisma3d.engine.PrismaEngineImpl
import com.prisma3d.importexport.ImportExportManager
import com.prisma3d.importexport.ImportExportManagerImpl
import com.prisma3d.modeling.ModelingToolManager
import com.prisma3d.modeling.ModelingToolManagerImpl
import com.prisma3d.selection.SelectionManager
import com.prisma3d.selection.SelectionManagerImpl
import com.prisma3d.settings.SettingsDataStore
import com.prisma3d.settings.SettingsDataStoreImpl
import com.prisma3d.ui.main.MainViewModel
import com.prisma3d.ui.modeling.ModelingViewModel
import com.prisma3d.ui.scene.SceneViewModel
import com.prisma3d.ui.settings.SettingsViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.components.ViewModelComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePrismaEngine(@ApplicationContext context: Context): PrismaEngine {
        return PrismaEngineImpl(context)
    }

    @Provides
    @Singleton
    fun provideSceneDatabase(@ApplicationContext context: Context): SceneDatabase {
        return Room.databaseBuilder(
            context,
            SceneDatabase::class.java,
            "prisma_scene.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideSceneRepository(database: SceneDatabase): SceneRepository {
        return SceneRepositoryImpl(database.sceneDao())
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore {
        val dataStore: RxPreferenceDataStoreDelegate<Preferences> = RxPreferenceDataStoreBuilder(
            context,
            "prisma_settings"
        ).build()
        return SettingsDataStoreImpl(dataStore)
    }

    @Provides
    @Singleton
    fun provideAssetManager(@ApplicationContext context: Context, engine: PrismaEngine): AssetManager {
        return AssetManagerImpl(context, engine)
    }

    @Provides
    @Singleton
    fun provideImportExportManager(
        @ApplicationContext context: Context,
        engine: PrismaEngine,
        assetManager: AssetManager,
        sceneRepository: SceneRepository
    ): ImportExportManager {
        return ImportExportManagerImpl(context, engine, assetManager, sceneRepository)
    }

    @Provides
    @Singleton
    fun provideModelingToolManager(engine: PrismaEngine): ModelingToolManager {
        return ModelingToolManagerImpl(engine)
    }

    @Provides
    @Singleton
    fun provideSelectionManager(engine: PrismaEngine): SelectionManager {
        return SelectionManagerImpl(engine)
    }

    @Provides
    @Singleton
    fun provideUndoRedoManager(): UndoRedoManager {
        return UndoRedoManagerImpl()
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class ViewModelModule {

    @Binds
    @IntoMap
    @ViewModelKey(MainViewModel::class)
    abstract fun bindMainViewModel(viewModel: MainViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(SceneViewModel::class)
    abstract fun bindSceneViewModel(viewModel: SceneViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(ModelingViewModel::class)
    abstract fun bindModelingViewModel(viewModel: ModelingViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(SettingsViewModel::class)
    abstract fun bindSettingsViewModel(viewModel: SettingsViewModel): ViewModel

    @Binds
    abstract fun bindViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory
}

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelFactoryModule {

    @Provides
    fun provideViewModelFactory(
        mainViewModelProvider: Provider<MainViewModel>,
        sceneViewModelProvider: Provider<SceneViewModel>,
        modelingViewModelProvider: Provider<ModelingViewModel>,
        settingsViewModelProvider: Provider<SettingsViewModel>
    ): ViewModelFactory {
        return ViewModelFactory(
            mapOf(
                "MainViewModel" to mainViewModelProvider,
                "SceneViewModel" to sceneViewModelProvider,
                "ModelingViewModel" to modelingViewModelProvider,
                "SettingsViewModel" to settingsViewModelProvider
            )
        )
    }
}

class ViewModelFactory @Inject constructor(
    private val viewModelProviders: Map<String, Provider<ViewModel>>
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val key = modelClass.simpleName
        val provider = viewModelProviders[key]
            ?: throw IllegalArgumentException("Unknown ViewModel class: $key")
        return provider.get() as T
    }
}