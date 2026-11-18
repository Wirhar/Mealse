package com.my.mealse.messenger.data.dao;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.my.mealse.messenger.data.entity.ParticipantEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParticipantDao_Impl implements ParticipantDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<ParticipantEntity> __insertionAdapterOfParticipantEntity;
    private final EntityDeletionOrUpdateAdapter<ParticipantEntity> __deletionAdapterOfParticipantEntity;
    private final SharedSQLiteStatement __preparedStmtOfDeleteByConversation;
    private final SharedSQLiteStatement __preparedStmtOfDeleteById;

    public ParticipantDao_Impl(@NonNull final RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapterOfParticipantEntity = new EntityInsertionAdapter<ParticipantEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `participants` (`id`,`conversation_id`,`node_key`,`fingerprint`,`device_id`,`display_name`,`role`,`joined_at`) VALUES (?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final ParticipantEntity entity) {
                if (entity.id == null) {
                    statement.bindNull(1);
                } else {
                    statement.bindString(1, entity.id);
                }
                if (entity.conversationId == null) {
                    statement.bindNull(2);
                } else {
                    statement.bindString(2, entity.conversationId);
                }
                if (entity.nodeKey == null) {
                    statement.bindNull(3);
                } else {
                    statement.bindString(3, entity.nodeKey);
                }
                if (entity.fingerprint == null) {
                    statement.bindNull(4);
                } else {
                    statement.bindString(4, entity.fingerprint);
                }
                if (entity.deviceId == null) {
                    statement.bindNull(5);
                } else {
                    statement.bindString(5, entity.deviceId);
                }
                if (entity.displayName == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindString(6, entity.displayName);
                }
                if (entity.role == null) {
                    statement.bindNull(7);
                } else {
                    statement.bindString(7, entity.role);
                }
                statement.bindLong(8, entity.joinedAt);
            }
        };
        this.__deletionAdapterOfParticipantEntity = new EntityDeletionOrUpdateAdapter<ParticipantEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "DELETE FROM `participants` WHERE `id` = ?";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final ParticipantEntity entity) {
                if (entity.id == null) {
                    statement.bindNull(1);
                } else {
                    statement.bindString(1, entity.id);
                }
            }
        };
        this.__preparedStmtOfDeleteByConversation = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM participants WHERE conversation_id = ?";
            }
        };
        this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM participants WHERE id = ?";
            }
        };
    }

    @Override
    public void insert(final ParticipantEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __insertionAdapterOfParticipantEntity.insert(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void insertAll(final List<ParticipantEntity> entities) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __insertionAdapterOfParticipantEntity.insert(entities);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void delete(final ParticipantEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __deletionAdapterOfParticipantEntity.handle(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void deleteByConversation(final String conversationId) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByConversation.acquire();
        int _argIndex = 1;
        if (conversationId == null) {
            _stmt.bindNull(_argIndex);
        } else {
            _stmt.bindString(_argIndex, conversationId);
        }
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfDeleteByConversation.release(_stmt);
        }
    }

    @Override
    public void deleteById(final String id) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        if (id == null) {
            _stmt.bindNull(_argIndex);
        } else {
            _stmt.bindString(_argIndex, id);
        }
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfDeleteById.release(_stmt);
        }
    }

    @Override
    public List<ParticipantEntity> getByConversation(final String conversationId) {
        final String _sql = "SELECT * FROM participants WHERE conversation_id = ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (conversationId == null) {
            _statement.bindNull(_argIndex);
        } else {
            _statement.bindString(_argIndex, conversationId);
        }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "node_key");
            final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
            final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "device_id");
            final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "display_name");
            final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
            final int _cursorIndexOfJoinedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "joined_at");
            final List<ParticipantEntity> _result = new ArrayList<ParticipantEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final ParticipantEntity _item;
                _item = new ParticipantEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfNodeKey)) { _item.nodeKey = null; } else { _item.nodeKey = _cursor.getString(_cursorIndexOfNodeKey); }
                if (_cursor.isNull(_cursorIndexOfFingerprint)) { _item.fingerprint = null; } else { _item.fingerprint = _cursor.getString(_cursorIndexOfFingerprint); }
                if (_cursor.isNull(_cursorIndexOfDeviceId)) { _item.deviceId = null; } else { _item.deviceId = _cursor.getString(_cursorIndexOfDeviceId); }
                if (_cursor.isNull(_cursorIndexOfDisplayName)) { _item.displayName = null; } else { _item.displayName = _cursor.getString(_cursorIndexOfDisplayName); }
                if (_cursor.isNull(_cursorIndexOfRole)) { _item.role = null; } else { _item.role = _cursor.getString(_cursorIndexOfRole); }
                _item.joinedAt = _cursor.getLong(_cursorIndexOfJoinedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public ParticipantEntity findByFingerprint(final String conversationId, final String fingerprint) {
        final String _sql = "SELECT * FROM participants WHERE conversation_id = ? AND fingerprint = ? LIMIT 1";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
        int _argIndex = 1;
        if (conversationId == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, conversationId); }
        _argIndex = 2;
        if (fingerprint == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, fingerprint); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "node_key");
            final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
            final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "device_id");
            final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "display_name");
            final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
            final int _cursorIndexOfJoinedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "joined_at");
            final ParticipantEntity _result;
            if (_cursor.moveToFirst()) {
                _result = new ParticipantEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _result.id = null; } else { _result.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _result.conversationId = null; } else { _result.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfNodeKey)) { _result.nodeKey = null; } else { _result.nodeKey = _cursor.getString(_cursorIndexOfNodeKey); }
                if (_cursor.isNull(_cursorIndexOfFingerprint)) { _result.fingerprint = null; } else { _result.fingerprint = _cursor.getString(_cursorIndexOfFingerprint); }
                if (_cursor.isNull(_cursorIndexOfDeviceId)) { _result.deviceId = null; } else { _result.deviceId = _cursor.getString(_cursorIndexOfDeviceId); }
                if (_cursor.isNull(_cursorIndexOfDisplayName)) { _result.displayName = null; } else { _result.displayName = _cursor.getString(_cursorIndexOfDisplayName); }
                if (_cursor.isNull(_cursorIndexOfRole)) { _result.role = null; } else { _result.role = _cursor.getString(_cursorIndexOfRole); }
                _result.joinedAt = _cursor.getLong(_cursorIndexOfJoinedAt);
            } else {
                _result = null;
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public int countByConversation(final String conversationId) {
        final String _sql = "SELECT COUNT(*) FROM participants WHERE conversation_id = ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (conversationId == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, conversationId); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _result;
            if (_cursor.moveToFirst()) { _result = _cursor.getInt(0); } else { _result = 0; }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @NonNull
    public static List<Class<?>> getRequiredConverters() {
        return Collections.emptyList();
    }
}