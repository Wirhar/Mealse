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
import com.my.mealse.messenger.data.entity.OutboxEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OutboxDao_Impl implements OutboxDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<OutboxEntity> __insertionAdapterOfOutboxEntity;
    private final EntityDeletionOrUpdateAdapter<OutboxEntity> __updateAdapterOfOutboxEntity;
    private final EntityDeletionOrUpdateAdapter<OutboxEntity> __deletionAdapterOfOutboxEntity;
    private final SharedSQLiteStatement __preparedStmtOfMarkAttempt;
    private final SharedSQLiteStatement __preparedStmtOfDeleteById;
    private final SharedSQLiteStatement __preparedStmtOfDeleteByMessage;
    private final SharedSQLiteStatement __preparedStmtOfDeleteByTarget;

    public OutboxDao_Impl(@NonNull final RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapterOfOutboxEntity = new EntityInsertionAdapter<OutboxEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `outbox` (`id`,`message_id`,`conversation_id`,`target_node_key`,`target_fingerprint`,`attempt_count`,`next_attempt_at`,`last_error`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final OutboxEntity entity) {
                if (entity.id == null) { statement.bindNull(1); } else { statement.bindString(1, entity.id); }
                if (entity.messageId == null) { statement.bindNull(2); } else { statement.bindString(2, entity.messageId); }
                if (entity.conversationId == null) { statement.bindNull(3); } else { statement.bindString(3, entity.conversationId); }
                if (entity.targetNodeKey == null) { statement.bindNull(4); } else { statement.bindString(4, entity.targetNodeKey); }
                if (entity.targetFingerprint == null) { statement.bindNull(5); } else { statement.bindString(5, entity.targetFingerprint); }
                statement.bindLong(6, entity.attemptCount);
                statement.bindLong(7, entity.nextAttemptAt);
                if (entity.lastError == null) { statement.bindNull(8); } else { statement.bindString(8, entity.lastError); }
                statement.bindLong(9, entity.createdAt);
            }
        };
        this.__updateAdapterOfOutboxEntity = new EntityDeletionOrUpdateAdapter<OutboxEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "UPDATE OR ABORT `outbox` SET `id` = ?,`message_id` = ?,`conversation_id` = ?,`target_node_key` = ?,`target_fingerprint` = ?,`attempt_count` = ?,`next_attempt_at` = ?,`last_error` = ?,`created_at` = ? WHERE `id` = ?";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final OutboxEntity entity) {
                if (entity.id == null) { statement.bindNull(1); } else { statement.bindString(1, entity.id); }
                if (entity.messageId == null) { statement.bindNull(2); } else { statement.bindString(2, entity.messageId); }
                if (entity.conversationId == null) { statement.bindNull(3); } else { statement.bindString(3, entity.conversationId); }
                if (entity.targetNodeKey == null) { statement.bindNull(4); } else { statement.bindString(4, entity.targetNodeKey); }
                if (entity.targetFingerprint == null) { statement.bindNull(5); } else { statement.bindString(5, entity.targetFingerprint); }
                statement.bindLong(6, entity.attemptCount);
                statement.bindLong(7, entity.nextAttemptAt);
                if (entity.lastError == null) { statement.bindNull(8); } else { statement.bindString(8, entity.lastError); }
                statement.bindLong(9, entity.createdAt);
                if (entity.id == null) { statement.bindNull(10); } else { statement.bindString(10, entity.id); }
            }
        };
        this.__deletionAdapterOfOutboxEntity = new EntityDeletionOrUpdateAdapter<OutboxEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "DELETE FROM `outbox` WHERE `id` = ?";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final OutboxEntity entity) {
                if (entity.id == null) { statement.bindNull(1); } else { statement.bindString(1, entity.id); }
            }
        };
        this.__preparedStmtOfMarkAttempt = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "UPDATE outbox SET attempt_count = attempt_count + 1, next_attempt_at = ?, last_error = ? WHERE id = ?";
            }
        };
        this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM outbox WHERE id = ?";
            }
        };
        this.__preparedStmtOfDeleteByMessage = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM outbox WHERE message_id = ?";
            }
        };
        this.__preparedStmtOfDeleteByTarget = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM outbox WHERE target_node_key = ?";
            }
        };
    }

    @Override
    public void insert(final OutboxEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __insertionAdapterOfOutboxEntity.insert(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void update(final OutboxEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __updateAdapterOfOutboxEntity.handle(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void delete(final OutboxEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __deletionAdapterOfOutboxEntity.handle(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public OutboxEntity getById(final String id) {
        final String _sql = "SELECT * FROM outbox WHERE id = ? LIMIT 1";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (id == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, id); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final OutboxEntity _result;
            if (_cursor.moveToFirst()) {
                _result = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _result.id = null; } else { _result.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _result.messageId = null; } else { _result.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _result.conversationId = null; } else { _result.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _result.targetNodeKey = null; } else { _result.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _result.targetFingerprint = null; } else { _result.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _result.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _result.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _result.lastError = null; } else { _result.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
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
    public List<OutboxEntity> getByMessage(final String messageId) {
        final String _sql = "SELECT * FROM outbox WHERE message_id = ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (messageId == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, messageId); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final List<OutboxEntity> _result = new ArrayList<OutboxEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final OutboxEntity _item;
                _item = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _item.messageId = null; } else { _item.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _item.targetNodeKey = null; } else { _item.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _item.targetFingerprint = null; } else { _item.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _item.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _item.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _item.lastError = null; } else { _item.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public List<OutboxEntity> getByTarget(final String nodeKey) {
        final String _sql = "SELECT * FROM outbox WHERE target_node_key = ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (nodeKey == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, nodeKey); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final List<OutboxEntity> _result = new ArrayList<OutboxEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final OutboxEntity _item;
                _item = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _item.messageId = null; } else { _item.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _item.targetNodeKey = null; } else { _item.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _item.targetFingerprint = null; } else { _item.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _item.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _item.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _item.lastError = null; } else { _item.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public List<OutboxEntity> getDue(final long now) {
        final String _sql = "SELECT * FROM outbox WHERE next_attempt_at <= ? ORDER BY next_attempt_at ASC";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        _statement.bindLong(_argIndex, now);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final List<OutboxEntity> _result = new ArrayList<OutboxEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final OutboxEntity _item;
                _item = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _item.messageId = null; } else { _item.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _item.targetNodeKey = null; } else { _item.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _item.targetFingerprint = null; } else { _item.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _item.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _item.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _item.lastError = null; } else { _item.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public List<OutboxEntity> getDueLimited(final long now, final int limit) {
        final String _sql = "SELECT * FROM outbox WHERE next_attempt_at <= ? ORDER BY next_attempt_at ASC LIMIT ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
        int _argIndex = 1;
        _statement.bindLong(_argIndex, now);
        _argIndex = 2;
        _statement.bindLong(_argIndex, limit);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final List<OutboxEntity> _result = new ArrayList<OutboxEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final OutboxEntity _item;
                _item = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _item.messageId = null; } else { _item.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _item.targetNodeKey = null; } else { _item.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _item.targetFingerprint = null; } else { _item.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _item.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _item.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _item.lastError = null; } else { _item.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public List<OutboxEntity> getAll() {
        final String _sql = "SELECT * FROM outbox ORDER BY next_attempt_at ASC";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "message_id");
            final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversation_id");
            final int _cursorIndexOfTargetNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor, "target_node_key");
            final int _cursorIndexOfTargetFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "target_fingerprint");
            final int _cursorIndexOfAttemptCount = CursorUtil.getColumnIndexOrThrow(_cursor, "attempt_count");
            final int _cursorIndexOfNextAttemptAt = CursorUtil.getColumnIndexOrThrow(_cursor, "next_attempt_at");
            final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "last_error");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final List<OutboxEntity> _result = new ArrayList<OutboxEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final OutboxEntity _item;
                _item = new OutboxEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfMessageId)) { _item.messageId = null; } else { _item.messageId = _cursor.getString(_cursorIndexOfMessageId); }
                if (_cursor.isNull(_cursorIndexOfConversationId)) { _item.conversationId = null; } else { _item.conversationId = _cursor.getString(_cursorIndexOfConversationId); }
                if (_cursor.isNull(_cursorIndexOfTargetNodeKey)) { _item.targetNodeKey = null; } else { _item.targetNodeKey = _cursor.getString(_cursorIndexOfTargetNodeKey); }
                if (_cursor.isNull(_cursorIndexOfTargetFingerprint)) { _item.targetFingerprint = null; } else { _item.targetFingerprint = _cursor.getString(_cursorIndexOfTargetFingerprint); }
                _item.attemptCount = _cursor.getInt(_cursorIndexOfAttemptCount);
                _item.nextAttemptAt = _cursor.getLong(_cursorIndexOfNextAttemptAt);
                if (_cursor.isNull(_cursorIndexOfLastError)) { _item.lastError = null; } else { _item.lastError = _cursor.getString(_cursorIndexOfLastError); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public void markAttempt(final String id, final long nextAt, final String error) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkAttempt.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, nextAt);
        _argIndex = 2;
        if (error == null) { _stmt.bindNull(_argIndex); } else { _stmt.bindString(_argIndex, error); }
        _argIndex = 3;
        if (id == null) { _stmt.bindNull(_argIndex); } else { _stmt.bindString(_argIndex, id); }
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfMarkAttempt.release(_stmt);
        }
    }

    @Override
    public void deleteById(final String id) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        if (id == null) { _stmt.bindNull(_argIndex); } else { _stmt.bindString(_argIndex, id); }
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
    public void deleteByMessage(final String messageId) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByMessage.acquire();
        int _argIndex = 1;
        if (messageId == null) { _stmt.bindNull(_argIndex); } else { _stmt.bindString(_argIndex, messageId); }
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfDeleteByMessage.release(_stmt);
        }
    }

    @Override
    public void deleteByTarget(final String nodeKey) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByTarget.acquire();
        int _argIndex = 1;
        if (nodeKey == null) { _stmt.bindNull(_argIndex); } else { _stmt.bindString(_argIndex, nodeKey); }
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfDeleteByTarget.release(_stmt);
        }
    }

    @Override
    public int count() {
        final String _sql = "SELECT COUNT(*) FROM outbox";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
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