package dev.apollo

import android.app.Application
import dev.apollo.calypso.SealKeys
import dev.apollo.calypso.SealedSecret
import dev.apollo.net.Facts
import dev.apollo.net.Push
import dev.apollo.net.Server
import dev.apollo.net.Sync
import java.io.File
import kotlinx.coroutines.launch

class ApolloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Clio logs from here on; a staged backup restore moves in before any store opens.
        dev.apollo.clio.Clio.init(this)
        // Every network client goes through NetGate from the first request on.
        dev.apollo.net.NetGate.init(this)
        dev.apollo.mnemosyne.Mnemosyne.applyStaged(this)
        // Zeus kept its wrapped data keys here; Apollo reads the same files.
        SealKeys.dir = File(noBackupFilesDir, "seal")
        // Calypso's escrow tap, while "Include Calypso" is on (Mnemosyne).
        runCatching { dev.apollo.mnemosyne.CalypsoEscrow.install(this) }
        // Before any sync can touch filesDir: Zeus's mini apps move aside, kept.
        dev.apollo.net.ZeusApps.keep(this)
        dev.apollo.senses.Senses.sink = dev.apollo.senses.FactSink { kind, data, urgent -> Facts.post(this, kind, data, urgent) }
        // Free actions tell him with Undo (actions/Undo.kt); the actions Zeus had come back with MoreActions.
        dev.apollo.net.Actions.current = dev.apollo.actions.Undo.around(dev.apollo.actions.PhoneActions)
        dev.apollo.actions.MoreActions.register()
        dev.apollo.actions.RoutineAlarm.arm(this)
        dev.apollo.house.House.init(this)
        dev.apollo.muse.Muse.register()
        dev.apollo.ui.Themes.load(this)
        dev.apollo.muse.SpotifyPlaylists.register()
        dev.apollo.live.Live.register()
        dev.apollo.mnemosyne.Mnemosyne.schedule(this)
        runCatching { dev.apollo.ossa.Ossa.register(this) }
        // Senses that need starting: calendar actions, stays and places, Health Connect (every 3 h and when Apollo comes to the front).
        dev.apollo.senses.CalendarSense.register()
        dev.apollo.actions.BirthdayContacts.register()
        runCatching { dev.apollo.senses.Places.start(this) }
        dev.apollo.senses.Health.schedule(this)
        // Walks (activity transitions, lost on reboot: armed again here), food and weight logs, folders he picked.
        runCatching { dev.apollo.senses.Walking.arm(this) }
        dev.apollo.senses.HealthWrite.register()
        dev.apollo.senses.DocFolders.schedule(this)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                dev.apollo.senses.Health.now(this@ApolloApp)
                // Dawn's status rows: a permission he just granted shows up when he comes back to Apollo.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { dev.apollo.senses.Setup.look(this@ApolloApp) } }
            }
        })
        Sync.schedule(this)
        dev.apollo.apps.Widgets.schedule(this)
        Facts.schedule(this)
        dev.apollo.clio.ClioShip.schedule(this)
        // FCM only once the phone has its device token (it is useless to the server before that).
        if (SealedSecret(this, Server.TOKEN_SLOT).has()) runCatching { Push.register(this) }
    }
}
