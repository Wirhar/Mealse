package com.my.mealse.messenger.data;

import androidx.room.RoomDatabase;
import com.my.mealse.messenger.data.dao.ConversationDao;
import com.my.mealse.messenger.data.dao.MessageDao;
import com.my.mealse.messenger.data.dao.OutboxDao;
import com.my.mealse.messenger.data.dao.ParticipantDao;

// УБРАЛИ @Database, чтобы Room не искал _Impl
public abstract class MealseDatabase extends RoomDatabase {
    public static final String DB_NAME = "mealse_messenger.db";
    public abstract ConversationDao conversationDao();
    public abstract ParticipantDao participantDao();
    public abstract MessageDao messageDao();
    public abstract OutboxDao outboxDao();
} хранения: getDatabasePath("mealse_messenger.db") (внутреннее).
 * Никаких fallback на внешнее хранилище — история переписки должна быть
 * защищена системными правами приложения.
 *
 * Схема экспортируется в app/schemas/ (см. annotationProcessorOptions
 * в app/build.gradle — добавим на Шаге 0.5).
 */
@Database(
    entities = {
        ConversationEntity.class,
        ParticipantEntity.class,
        MessageEntity.class,
        OutboxEntity.class
    },
    version = 1,
    exportSchema = true
)
public abstract class MealseDatabase extends RoomDatabase {

    public static final String DB_NAME = "mealse_messenger.db";

    public abstract ConversationDao conversationDao();

    public abstract ParticipantDao participantDao();

    public abstract MessageDao messageDao();

    public abstract OutboxDao outboxDao();
}