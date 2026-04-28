package com.example.bluechats.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

@Database(entities = {ContactEntity.class, MessageEntity.class, OutboxEntity.class,
        MessageQueueEntity.class, MyChatEntity.class}, version = 2, exportSchema = false)
@TypeConverters({StringListConverter.class})
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    public abstract ContactDao contactDao();
    public abstract MessageDao messageDao();
    public abstract OutboxDao outboxDao();
    public abstract MessageQueueDao messageQueueDao();
    public abstract MyChatDao myChatDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "bluechats_database")
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }
}
