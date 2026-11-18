package com.my.mealse.messenger.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.InvalidationTracker;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import com.my.mealse.messenger.data.dao.ConversationDao;
import com.my.mealse.messenger.data.dao.ConversationDao_Impl;
import com.my.mealse.messenger.data.dao.MessageDao;
import com.my.mealse.messenger.data.dao.MessageDao_Impl;
import com.my.mealse.messenger.data.dao.OutboxDao;
import com.my.mealse.messenger.data.dao.OutboxDao_Impl;
import com.my.mealse.messenger.data.dao.ParticipantDao;
import com.my.mealse.messenger.data.dao.ParticipantDao_Impl;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MealseDatabaseImpl extends MealseDatabase {
    private volatile ConversationDao _conversationDao;
    private volatile ParticipantDao _participantDao;
    private volatile MessageDao _messageDao;
    private volatile OutboxDao _outboxDao;

    // Конструктор, который создает базу и инициализирует внутренние поля Room
    public static MealseDatabaseImpl create(Context context) {
        MealseDatabaseImpl db = Room.databaseBuilder(context, MealseDatabaseImpl.class, MealseDatabase.DB_NAME)
                .allowMainThreadQueries() // Временно, чтобы избежать проблем с потоками
                .fallbackToDestructiveMigration()
                .build();
        return db;
    }

    @Override
    @NonNull
    protected SupportSQLiteOpenHelper createOpenHelper(androidx.room.DatabaseConfiguration configuration) {
        SupportSQLiteOpenHelper.Configuration config = SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(MealseDatabase.DB_NAME)
                .callback(new SupportSQLiteOpenHelper.Callback(1) {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_message_id` TEXT, `last_message_preview` TEXT, `last_message_at` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, `peer_fingerprint` TEXT, `call_id` TEXT, PRIMARY KEY(`id`))");
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_call_id` ON `conversations` (`call_id`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_updated_at` ON `conversations` (`updated_at`)");
                        db.execSQL("CREATE TABLE IF NOT EXISTS `participants` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `node_key` TEXT, `fingerprint` TEXT, `device_id` TEXT, `display_name` TEXT, `role` TEXT NOT NULL, `joined_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_participants_conversation_id` ON `participants` (`conversation_id`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_participants_fingerprint` ON `participants` (`fingerprint`)");
                        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `sender_fingerprint` TEXT, `sender_device_id` TEXT, `sender_node_key` TEXT, `type` TEXT NOT NULL, `body` TEXT, `encrypted_body` BLOB, `attachment_uri` TEXT, `attachment_name` TEXT, `attachment_mime` TEXT, `attachment_size` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `delivered_at` INTEGER NOT NULL, `read_at` INTEGER NOT NULL, `reply_to_message_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_created_at` ON `messages` (`conversation_id`, `created_at`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversation_id_status` ON `messages` (`conversation_id`, `status`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sender_fingerprint` ON `messages` (`sender_fingerprint`)");
                        db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `target_node_key` TEXT NOT NULL, `target_fingerprint` TEXT, `attempt_count` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_message_id` ON `outbox` (`message_id`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_next_attempt_at` ON `outbox` (`next_attempt_at`)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_target_node_key` ON `outbox` (`target_node_key`)");
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd7b0a87f2cb0ffb4b7f8b7e7c5a4e3d2')");
                    }

                    @Override
                    public void onUpgrade(@NonNull SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                        db.execSQL("DROP TABLE IF EXISTS `conversations`");
                        db.execSQL("DROP TABLE IF EXISTS `participants`");
                        db.execSQL("DROP TABLE IF EXISTS `messages`");
                        db.execSQL("DROP TABLE IF EXISTS `outbox`");
                        onCreate(db);
                    }
                }).build();
        return new FrameworkSQLiteOpenHelperFactory().create(config);
    }

    @Override
    @NonNull
    protected InvalidationTracker createInvalidationTracker() {
        final HashMap<String, String> _shadowTablesMap = new HashMap<>();
        HashMap<String, Set<String>> _viewTables = new HashMap<>();
        return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "conversations", "participants", "messages", "outbox");
    }

    @Override
    public void clearAllTables() {
        super.assertNotMainThread();
        final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
        try {
            super.beginTransaction();
            _db.execSQL("DELETE FROM `conversations`");
            _db.execSQL("DELETE FROM `participants`");
            _db.execSQL("DELETE FROM `messages`");
            _db.execSQL("DELETE FROM `outbox`");
            super.setTransactionSuccessful();
        } finally {
            super.endTransaction();
        }
        _db.query("PRAGMA wal_checkpoint(FULL)").close();
        if (!_db.inTransaction()) { _db.execSQL("VACUUM"); }
    }

    @Override
    @NonNull
    protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
        final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<>();
        _typeConvertersMap.put(ConversationDao.class, ConversationDao_Impl.getRequiredConverters());
        _typeConvertersMap.put(ParticipantDao.class, ParticipantDao_Impl.getRequiredConverters());
        _typeConvertersMap.put(MessageDao.class, MessageDao_Impl.getRequiredConverters());
        _typeConvertersMap.put(OutboxDao.class, OutboxDao_Impl.getRequiredConverters());
        return _typeConvertersMap;
    }

    @Override
    @NonNull
    public ConversationDao conversationDao() {
        if (_conversationDao != null) return _conversationDao;
        synchronized(this) {
            if(_conversationDao == null) {
                _conversationDao = new ConversationDao_Impl(this);
            }
            return _conversationDao;
        }
    }

    @Override
    @NonNull
    public ParticipantDao participantDao() {
        if (_participantDao != null) return _participantDao;
        synchronized(this) {
            if(_participantDao == null) {
                _participantDao = new ParticipantDao_Impl(this);
            }
            return _participantDao;
        }
    }

    @Override
    @NonNull
    public MessageDao messageDao() {
        if (_messageDao != null) return _messageDao;
        synchronized(this) {
            if(_messageDao == null) {
                _messageDao = new MessageDao_Impl(this);
            }
            return _messageDao;
        }
    }

    @Override
    @NonNull
    public OutboxDao outboxDao() {
        if (_outboxDao != null) return _outboxDao;
        synchronized(this) {
            if(_outboxDao == null) {
                _outboxDao = new OutboxDao_Impl(this);
            }
            return _outboxDao;
        }
    }
}