package pl.mtajchert.clipboardhero.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import pl.mtajchert.clipboardhero.ImageClipboardRepository
import pl.mtajchert.clipboardhero.ImageTransformer
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import javax.inject.Singleton

/**
 * Bindings for the app's collaborators. These classes have no framework
 * dependencies of their own (just an application [Context]) and stay
 * constructor-pure so the existing unit tests can keep instantiating them
 * directly — Hilt only owns how they're wired in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providesImageTransformer(): ImageTransformer = ImageTransformer()

    @Provides
    @Singleton
    fun providesImageClipboardRepository(
        @ApplicationContext context: Context,
        transformer: ImageTransformer,
    ): ImageClipboardRepository = ImageClipboardRepository(context, transformer)

    @Provides
    @Singleton
    fun providesSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository.create(context)
}
