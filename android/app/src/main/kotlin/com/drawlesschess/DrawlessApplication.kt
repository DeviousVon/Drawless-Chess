package com.drawlesschess

import android.app.Application
import com.drawlesschess.persistence.RoomCheckpointStore

class DrawlessApplication : Application() {
    internal val checkpointStore: RoomCheckpointStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomCheckpointStore.create(this)
    }
}
