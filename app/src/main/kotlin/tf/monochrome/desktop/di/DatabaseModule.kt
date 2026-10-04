package tf.monochrome.desktop.di

import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.Dispatchers
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.data.db.MusicDatabase
import tf.monochrome.desktop.data.db.dao.DownloadDao
import tf.monochrome.desktop.data.db.dao.EqPresetDao
import tf.monochrome.desktop.data.db.dao.FavoriteDao
import tf.monochrome.desktop.data.db.dao.HistoryDao
import tf.monochrome.desktop.data.db.dao.MixPresetDao
import tf.monochrome.desktop.data.db.dao.PlayEventDao
import tf.monochrome.desktop.data.db.dao.PlaylistDao
import javax.inject.Singleton

@Module
object DatabaseModule {

    /**
     * Desktop: Room's JVM builder takes the database file's full path instead of
     * a Context and a name (`AppPaths.dbFile`, `%LOCALAPPDATA%\Tryptify\data\monochrome_db`
     * on Windows), runs on the bundled SQLite through [BundledSQLiteDriver], and
     * dispatches its suspend and Flow queries on [Dispatchers.IO]. Migrations and
     * the destructive-migration callback are the Android app's.
     */
    @Provides
    @Singleton
    fun provideDatabase(paths: AppPaths): MusicDatabase {
        return Room.databaseBuilder<MusicDatabase>(name = paths.dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(
                MusicDatabase.MIGRATION_8_9,
                MusicDatabase.MIGRATION_9_10,
                MusicDatabase.MIGRATION_10_11,
                MusicDatabase.MIGRATION_11_12,
                MusicDatabase.MIGRATION_12_13,
                MusicDatabase.MIGRATION_13_14,
            )
            // Retained as a safety net for any version gap without an explicit
            // migration; the THX (8→9) and Atmos (9→10) upgrades migrate in
            // place above.
            .fallbackToDestructiveMigration(dropAllTables = true)
            // …and it says so when it fires. This drops every table the user
            // owns — playlists, favourites, history, presets — and it did it
            // silently, so the app came back looking like a fresh install with
            // no record anywhere of why. A dropped database is recoverable for
            // a signed-in listener (LibraryRestoreCoordinator pulls it back)
            // and unrecoverable for everyone else, and either way it is the
            // single most destructive thing this app does to its own data. It
            // does not get to be quiet about it.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(connection: SQLiteConnection) {
                    Log.e(
                        "MusicDatabase",
                        "Destructive migration: every local table was dropped " +
                            "(schema mismatch or a version gap with no migration). " +
                            "Local library data is gone; a signed-in account will " +
                            "restore from the cloud on next launch.",
                    )
                }
            })
            .build()
    }

    @Provides
    fun provideFavoriteDao(db: MusicDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlaybackStateDao(db: MusicDatabase): tf.monochrome.desktop.data.db.dao.PlaybackStateDao = db.playbackStateDao()

    @Provides
    fun provideHistoryDao(db: MusicDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlayEventDao(db: MusicDatabase): PlayEventDao = db.playEventDao()

    @Provides
    fun providePlaylistDao(db: MusicDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideDownloadDao(db: MusicDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideEqPresetDao(db: MusicDatabase): EqPresetDao = db.eqPresetDao()

    @Provides
    fun provideMixPresetDao(db: MusicDatabase): MixPresetDao = db.mixPresetDao()
}
