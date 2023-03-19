package com.example.websocketflowexample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.websocketflowexample.di.KoinModule
import com.example.websocketflowexample.ui.main.MainFragment
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.mp.KoinPlatformTools
import timber.log.Timber

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (KoinPlatformTools.defaultContext().getOrNull() == null) {
            Timber.plant(Timber.DebugTree())

            startKoin {
                androidLogger(Level.DEBUG)
                androidContext(this@MainActivity)
                modules(listOf(KoinModule.module()))
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MainFragment())
                .commitNow()
        }
    }
}