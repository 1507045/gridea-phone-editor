package org.eu.gjry.gridea_PRO

import android.app.Application
import org.eu.gjry.gridea_PRO.state.AppContainer

class GrideaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
