package org.eu.gjry.gridea_PRO.state

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.eu.gjry.gridea_PRO.data.Prefs
import org.eu.gjry.gridea_PRO.data.Vault
import org.eu.gjry.gridea_PRO.sync.SyncEngine
import java.io.Closeable

/**
 * 手搓的依赖容器。这个 app 就三个对象互相依赖，引 Hilt 属于用大炮打蚊子。
 */
class AppContainer(context: Context) : Closeable {

    val prefs: Prefs = Prefs(context)

    val vault: Vault = Vault(context, prefs)

    /** 界面与后台任务共用；SupervisorJob 保证一个同步挂掉不会带走整个 app 的协程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 压缩包通道要在 cache 里解压，把 cacheDir 交给同步引擎当临时目录的父目录。 */
    val sync: SyncEngine = SyncEngine(vault, prefs, scope, context.applicationContext.cacheDir)

    val appState: AppState = AppState(vault, prefs, sync, scope)

    override fun close() {
        // 没有需要显式释放的资源；留着这个方法是为了以后加缓存时有落点
    }
}
