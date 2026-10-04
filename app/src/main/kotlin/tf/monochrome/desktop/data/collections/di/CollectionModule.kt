package tf.monochrome.desktop.data.collections.di

import dagger.Module
import dagger.Provides
import tf.monochrome.desktop.data.collections.db.CollectionDao
import tf.monochrome.desktop.data.db.MusicDatabase

@Module
object CollectionModule {

    @Provides
    fun provideCollectionDao(db: MusicDatabase): CollectionDao = db.collectionDao()
}
