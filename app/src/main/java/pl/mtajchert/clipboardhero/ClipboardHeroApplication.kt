package pl.mtajchert.clipboardhero

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. [@HiltAndroidApp] triggers Hilt's code generation and
 * hosts the singleton dependency graph (`SingletonComponent`) that backs every
 * [@AndroidEntryPoint] activity and [@HiltViewModel].
 */
@HiltAndroidApp
class ClipboardHeroApplication : Application()
