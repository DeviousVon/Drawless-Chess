package com.drawlesschess

import android.app.Application
import com.drawlesschess.persistence.RoomCheckpointStore
import com.drawlesschess.ui.GameSoundPlayer

class DrawlessApplication : Application() {
    internal lateinit var gameSoundPlayer: GameSoundPlayer
        private set

    internal val checkpointStore: RoomCheckpointStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomCheckpointStore.create(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Start the tiny procedural sound library before the player can enter or resume a game.
        gameSoundPlayer = GameSoundPlayer()
    }

    override fun onTerminate() {
        gameSoundPlayer.close()
        super.onTerminate()
    }
}
