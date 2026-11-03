package id.tomz.transcriptor.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideTranscriptDatabase(@ApplicationContext context: Context): TranscriptDatabase =
        Room.databaseBuilder(context, TranscriptDatabase::class.java, "transcriptor.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideTranscriptDao(database: TranscriptDatabase): TranscriptDao = database.transcriptDao()
}
