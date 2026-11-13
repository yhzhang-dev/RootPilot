package com.hangge.rootpilot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeviceSnapshotEntity::class, ActionRecordEntity::class, ApiRequestEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RootPilotDatabase : RoomDatabase() {
    abstract fun deviceSnapshotDao(): DeviceSnapshotDao
    abstract fun actionRecordDao(): ActionRecordDao
    abstract fun apiRequestDao(): ApiRequestDao

    companion object {
        const val NAME = "rootpilot.db"

        fun build(context: Context): RootPilotDatabase =
            Room.databaseBuilder(context, RootPilotDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
