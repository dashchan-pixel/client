package chan.content

import android.graphics.drawable.Drawable
import android.net.Uri

class Chan internal constructor(
    @JvmField val name: String?,
    @JvmField val packageName: String,
    @JvmField val configuration: ChanConfiguration,
    @JvmField val performer: ChanPerformer,
    @JvmField val locator: ChanLocator,
    @JvmField val markup: ChanMarkup,
    @JvmField internal val icon: Drawable?,
) {
    internal class Provider(
        private var chan: Chan?,
    ) {
        fun get(): Chan {
            var chan = this.chan
            if (chan == null) {
                synchronized(this) {
                    chan = this.chan
                }
            }
            return chan ?: error("Chan is not initialized")
        }

        fun set(chan: Chan) {
            synchronized(this) {
                check(this.chan == null)
                this.chan = chan
            }
        }
    }

    interface Linked {
        fun init()

        fun get(): Chan
    }

    companion object {
        @JvmStatic
        fun get(chanName: String?): Chan = ChanManager.getInstance().getChan(chanName)

        @JvmStatic
        fun getPreferred(
            chanName: String?,
            uri: Uri?,
        ): Chan = get(chanName ?: uri?.let { ChanManager.getInstance().getChanNameByHost(it.authority) })

        @JvmStatic
        fun getFallback(): Chan = ChanManager.getInstance().fallbackChan
    }
}
