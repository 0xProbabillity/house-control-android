package dev.housecontrol.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Holds the one [AppModel] of the process: the activity, the widgets and the tile share it. */
class HouseApp : Application() {
    val model: AppModel by lazy { AppModel(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.setActive(true)
            override fun onStop(owner: LifecycleOwner) = model.setActive(false)
        })
    }

    companion object {
        fun model(context: Context) = (context.applicationContext as HouseApp).model
    }
}
