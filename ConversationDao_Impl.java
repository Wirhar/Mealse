package com.my.mealse.messenger.data.dao;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.my.mealse.messenger.data.entity.ConversationEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

public final class ConversationDao_Impl implements ConversationDao {
    private final RoomDatabase __db;
    private final EntityInsertionAdapter<ConversationEntity> __insertionAdapterOfConversationEntity;
    private final EntityDeletionOrUpdateAdapter<ConversationEntity> __updateAdapterOfConversationEntity;
    private final SharedSQLiteStatement __preparedStmtOfTouchUpdatedAt;
    private final SharedSQLiteStatement __preparedStmtOfIncrementUnread;
    private final SharedSQLiteStatement __preparedStmtOfResetUnread;
    private final SharedSQLiteStatement __preparedStmtOfUpdateLastMessage;
    private final SharedSQLiteStatement __preparedStmtOfDeleteById;
    private final SharedSQLiteStatement __preparedStmtOfDeleteAll;

    public ConversationDao_Impl(@NonNull final RoomDatabase __db) {
        this.__db = __db;
        this.__insertionAdapterOfConversationEntity = new EntityInsertionAdapter<ConversationEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "INSERT OR REPLACE INTO `conversations` (`id`,`type`,`title`,`created_at`,`updated_at`,`last_message_id`,`last_message_preview`,`last_message_at`,`unread_count`,`peer_fingerprint`,`call_id`) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final ConversationEntity entity) {
                if (entity.id == null) {
                    statement.bindNull(1);
                } else {
                    statement.bindString(1, entity.id);
                }
                if (entity.type == null) {
                    statement.bindNull(2);
                } else {
                    statement.bindString(2, entity.type);
                }
                if (entity.title == null) {
                    statement.bindNull(3);
                } else {
                    statement.bindString(3, entity.title);
                }
                statement.bindLong(4, entity.createdAt);
                statement.bindLong(5, entity.updatedAt);
                if (entity.lastMessageId == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindString(6, entity.lastMessageId);
                }
                if (entity.lastMessagePreview == null) {
                    statement.bindNull(7);
                } else {
                    statement.bindString(7, entity.lastMessagePreview);
                }
                statement.bindLong(8, entity.lastMessageAt);
                statement.bindLong(9, entity.unreadCount);
                if (entity.peerFingerprint == null) {
                    statement.bindNull(10);
                } else {
                    statement.bindString(10, entity.peerFingerprint);
                }
                if (entity.callId == null) {
                    statement.bindNull(11);
                } else {
                    statement.bindString(11, entity.callId);
                }
            }
        };

        this.__updateAdapterOfConversationEntity = new EntityDeletionOrUpdateAdapter<ConversationEntity>(__db) {
            @Override
            @NonNull
            protected String createQuery() {
                return "UPDATE OR ABORT `conversations` SET `id` = ?,`type` = ?,`title` = ?,`created_at` = ?,`updated_at` = ?,`last_message_id` = ?,`last_message_preview` = ?,`last_message_at` = ?,`unread_count` = ?,`peer_fingerprint` = ?,`call_id` = ? WHERE `id` = ?";
            }

            @Override
            protected void bind(@NonNull final SupportSQLiteStatement statement, @NonNull final ConversationEntity entity) {
                if (entity.id == null) {
                    statement.bindNull(1);
                } else {
                    statement.bindString(1, entity.id);
                }
                if (entity.type == null) {
                    statement.bindNull(2);
                } else {
                    statement.bindString(2, entity.type);
                }
                if (entity.title == null) {
                    statement.bindNull(3);
                } else {
                    statement.bindString(3, entity.title);
                }
                statement.bindLong(4, entity.createdAt);
                statement.bindLong(5, entity.updatedAt);
                if (entity.lastMessageId == null) {
                    statement.bindNull(6);
                } else {
                    statement.bindString(6, entity.lastMessageId);
                }
                if (entity.lastMessagePreview == null) {
                    statement.bindNull(7);
                } else {
                    statement.bindString(7, entity.lastMessagePreview);
                }
                statement.bindLong(8, entity.lastMessageAt);
                statement.bindLong(9, entity.unreadCount);
                if (entity.peerFingerprint == null) {
                    statement.bindNull(10);
                } else {
                    statement.bindString(10, entity.peerFingerprint);
                }
                if (entity.callId == null) {
                    statement.bindNull(11);
                } else {
                    statement.bindString(11, entity.callId);
                }
                if (entity.id == null) {
                    statement.bindNull(12);
                } else {
                    statement.bindString(12, entity.id);
                }
            }
        };

        this.__preparedStmtOfTouchUpdatedAt = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "UPDATE conversations SET updated_at = ? WHERE id = ?";
            }
        };

        this.__preparedStmtOfIncrementUnread = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "UPDATE conversations SET unread_count = unread_count + 1 WHERE id = ?";
            }
        };

        this.__preparedStmtOfResetUnread = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "UPDATE conversations SET unread_count = 0 WHERE id = ?";
            }
        };

        this.__preparedStmtOfUpdateLastMessage = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "UPDATE conversations SET last_message_id = ?, last_message_preview = ?, last_message_at = ?, updated_at = ? WHERE id = ?";
            }
        };

        this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM conversations WHERE id = ?";
            }
        };

        this.__preparedStmtOfDeleteAll = new SharedSQLiteStatement(__db) {
            @Override
            @NonNull
            public String createQuery() {
                return "DELETE FROM conversations";
            }
        };
    }

    @Override
    public void insert(final ConversationEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __insertionAdapterOfConversationEntity.insert(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void update(final ConversationEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __updateAdapterOfConversationEntity.handle(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void delete(final ConversationEntity entity) {
        __db.assertNotSuspendingTransaction();
        __db.beginTransaction();
        try {
            __updateAdapterOfConversationEntity.handle(entity);
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
        }
    }

    @Override
    public void touchUpdatedAt(final String id, final long ts) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfTouchUpdatedAt.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 2;
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
            __preparedStmtOfTouchUpdatedAt.release(_stmt);
        }
    }

    @Override
    public void incrementUnread(final String id) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfIncrementUnread.acquire();
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
            __preparedStmtOfIncrementUnread.release(_stmt);
        }
    }

    @Override
    public void resetUnread(final String id) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfResetUnread.acquire();
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
            __preparedStmtOfResetUnread.release(_stmt);
        }
    }

    @Override
    public void updateLastMessage(final String id, final String messageId, final String preview, final long ts) {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateLastMessage.acquire();
        int _argIndex = 1;
        if (messageId == null) {
            _stmt.bindNull(_argIndex);
        } else {
            _stmt.bindString(_argIndex, messageId);
        }
        _argIndex = 2;
        if (preview == null) {
            _stmt.bindNull(_argIndex);
        } else {
            _stmt.bindString(_argIndex, preview);
        }
        _argIndex = 3;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 4;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 5;
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
            __preparedStmtOfUpdateLastMessage.release(_stmt);
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
    public void deleteAll() {
        __db.assertNotSuspendingTransaction();
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAll.acquire();
        __db.beginTransaction();
        try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
        } finally {
            __db.endTransaction();
            __preparedStmtOfDeleteAll.release(_stmt);
        }
    }

    @Override
    public ConversationEntity getById(final String id) {
        final String _sql = "SELECT * FROM conversations WHERE id = ? LIMIT 1";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (id == null) {
            _statement.bindNull(_argIndex);
        } else {
            _statement.bindString(_argIndex, id);
        }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
            final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
            final int _cursorIndexOfLastMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_id");
            final int _cursorIndexOfLastMessagePreview = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_preview");
            final int _cursorIndexOfLastMessageAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_at");
            final int _cursorIndexOfUnreadCount = CursorUtil.getColumnIndexOrThrow(_cursor, "unread_count");
            final int _cursorIndexOfPeerFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "peer_fingerprint");
            final int _cursorIndexOfCallId = CursorUtil.getColumnIndexOrThrow(_cursor, "call_id");
            final ConversationEntity _result;
            if (_cursor.moveToFirst()) {
                _result = new ConversationEntity();
                if (_cursor.isNull(_cursorIndexOfId)) {
                    _result.id = null;
                } else {
                    _result.id = _cursor.getString(_cursorIndexOfId);
                }
                if (_cursor.isNull(_cursorIndexOfType)) {
                    _result.type = null;
                } else {
                    _result.type = _cursor.getString(_cursorIndexOfType);
                }
                if (_cursor.isNull(_cursorIndexOfTitle)) {
                    _result.title = null;
                } else {
                    _result.title = _cursor.getString(_cursorIndexOfTitle);
                }
                _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
                if (_cursor.isNull(_cursorIndexOfLastMessageId)) {
                    _result.lastMessageId = null;
                } else {
                    _result.lastMessageId = _cursor.getString(_cursorIndexOfLastMessageId);
                }
                if (_cursor.isNull(_cursorIndexOfLastMessagePreview)) {
                    _result.lastMessagePreview = null;
                } else {
                    _result.lastMessagePreview = _cursor.getString(_cursorIndexOfLastMessagePreview);
                }
                _result.lastMessageAt = _cursor.getLong(_cursorIndexOfLastMessageAt);
                _result.unreadCount = _cursor.getInt(_cursorIndexOfUnreadCount);
                if (_cursor.isNull(_cursorIndexOfPeerFingerprint)) {
                    _result.peerFingerprint = null;
                } else {
                    _result.peerFingerprint = _cursor.getString(_cursorIndexOfPeerFingerprint);
                }
                if (_cursor.isNull(_cursorIndexOfCallId)) {
                    _result.callId = null;
                } else {
                    _result.callId = _cursor.getString(_cursorIndexOfCallId);
                }
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
    public ConversationEntity getByCallId(final String callId) {
        final String _sql = "SELECT * FROM conversations WHERE call_id = ? LIMIT 1";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        if (callId == null) {
            _statement.bindNull(_argIndex);
        } else {
            _statement.bindString(_argIndex, callId);
        }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
            final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
            final int _cursorIndexOfLastMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_id");
            final int _cursorIndexOfLastMessagePreview = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_preview");
            final int _cursorIndexOfLastMessageAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_at");
            final int _cursorIndexOfUnreadCount = CursorUtil.getColumnIndexOrThrow(_cursor, "unread_count");
            final int _cursorIndexOfPeerFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "peer_fingerprint");
            final int _cursorIndexOfCallId = CursorUtil.getColumnIndexOrThrow(_cursor, "call_id");
            final ConversationEntity _result;
            if (_cursor.moveToFirst()) {
                _result = new ConversationEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _result.id = null; } else { _result.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfType)) { _result.type = null; } else { _result.type = _cursor.getString(_cursorIndexOfType); }
                if (_cursor.isNull(_cursorIndexOfTitle)) { _result.title = null; } else { _result.title = _cursor.getString(_cursorIndexOfTitle); }
                _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
                if (_cursor.isNull(_cursorIndexOfLastMessageId)) { _result.lastMessageId = null; } else { _result.lastMessageId = _cursor.getString(_cursorIndexOfLastMessageId); }
                if (_cursor.isNull(_cursorIndexOfLastMessagePreview)) { _result.lastMessagePreview = null; } else { _result.lastMessagePreview = _cursor.getString(_cursorIndexOfLastMessagePreview); }
                _result.lastMessageAt = _cursor.getLong(_cursorIndexOfLastMessageAt);
                _result.unreadCount = _cursor.getInt(_cursorIndexOfUnreadCount);
                if (_cursor.isNull(_cursorIndexOfPeerFingerprint)) { _result.peerFingerprint = null; } else { _result.peerFingerprint = _cursor.getString(_cursorIndexOfPeerFingerprint); }
                if (_cursor.isNull(_cursorIndexOfCallId)) { _result.callId = null; } else { _result.callId = _cursor.getString(_cursorIndexOfCallId); }
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
    public ConversationEntity findPrivateByPeer(final String type, final String fingerprint) {
        final String _sql = "SELECT * FROM conversations WHERE type = ? AND peer_fingerprint = ? LIMIT 1";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
        int _argIndex = 1;
        if (type == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, type); }
        _argIndex = 2;
        if (fingerprint == null) { _statement.bindNull(_argIndex); } else { _statement.bindString(_argIndex, fingerprint); }
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
            final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
            final int _cursorIndexOfLastMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_id");
            final int _cursorIndexOfLastMessagePreview = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_preview");
            final int _cursorIndexOfLastMessageAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_at");
            final int _cursorIndexOfUnreadCount = CursorUtil.getColumnIndexOrThrow(_cursor, "unread_count");
            final int _cursorIndexOfPeerFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "peer_fingerprint");
            final int _cursorIndexOfCallId = CursorUtil.getColumnIndexOrThrow(_cursor, "call_id");
            final ConversationEntity _result;
            if (_cursor.moveToFirst()) {
                _result = new ConversationEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _result.id = null; } else { _result.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfType)) { _result.type = null; } else { _result.type = _cursor.getString(_cursorIndexOfType); }
                if (_cursor.isNull(_cursorIndexOfTitle)) { _result.title = null; } else { _result.title = _cursor.getString(_cursorIndexOfTitle); }
                _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _result.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
                if (_cursor.isNull(_cursorIndexOfLastMessageId)) { _result.lastMessageId = null; } else { _result.lastMessageId = _cursor.getString(_cursorIndexOfLastMessageId); }
                if (_cursor.isNull(_cursorIndexOfLastMessagePreview)) { _result.lastMessagePreview = null; } else { _result.lastMessagePreview = _cursor.getString(_cursorIndexOfLastMessagePreview); }
                _result.lastMessageAt = _cursor.getLong(_cursorIndexOfLastMessageAt);
                _result.unreadCount = _cursor.getInt(_cursorIndexOfUnreadCount);
                if (_cursor.isNull(_cursorIndexOfPeerFingerprint)) { _result.peerFingerprint = null; } else { _result.peerFingerprint = _cursor.getString(_cursorIndexOfPeerFingerprint); }
                if (_cursor.isNull(_cursorIndexOfCallId)) { _result.callId = null; } else { _result.callId = _cursor.getString(_cursorIndexOfCallId); }
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
    public List<ConversationEntity> getAll() {
        final String _sql = "SELECT * FROM conversations ORDER BY updated_at DESC";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
            final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
            final int _cursorIndexOfLastMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_id");
            final int _cursorIndexOfLastMessagePreview = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_preview");
            final int _cursorIndexOfLastMessageAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_at");
            final int _cursorIndexOfUnreadCount = CursorUtil.getColumnIndexOrThrow(_cursor, "unread_count");
            final int _cursorIndexOfPeerFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "peer_fingerprint");
            final int _cursorIndexOfCallId = CursorUtil.getColumnIndexOrThrow(_cursor, "call_id");
            final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final ConversationEntity _item;
                _item = new ConversationEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfType)) { _item.type = null; } else { _item.type = _cursor.getString(_cursorIndexOfType); }
                if (_cursor.isNull(_cursorIndexOfTitle)) { _item.title = null; } else { _item.title = _cursor.getString(_cursorIndexOfTitle); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
                if (_cursor.isNull(_cursorIndexOfLastMessageId)) { _item.lastMessageId = null; } else { _item.lastMessageId = _cursor.getString(_cursorIndexOfLastMessageId); }
                if (_cursor.isNull(_cursorIndexOfLastMessagePreview)) { _item.lastMessagePreview = null; } else { _item.lastMessagePreview = _cursor.getString(_cursorIndexOfLastMessagePreview); }
                _item.lastMessageAt = _cursor.getLong(_cursorIndexOfLastMessageAt);
                _item.unreadCount = _cursor.getInt(_cursorIndexOfUnreadCount);
                if (_cursor.isNull(_cursorIndexOfPeerFingerprint)) { _item.peerFingerprint = null; } else { _item.peerFingerprint = _cursor.getString(_cursorIndexOfPeerFingerprint); }
                if (_cursor.isNull(_cursorIndexOfCallId)) { _item.callId = null; } else { _item.callId = _cursor.getString(_cursorIndexOfCallId); }
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public List<ConversationEntity> getRecent(final int limit) {
        final String _sql = "SELECT * FROM conversations ORDER BY updated_at DESC LIMIT ?";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
        int _argIndex = 1;
        _statement.bindLong(_argIndex, limit);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
            final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
            final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
            final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
            final int _cursorIndexOfLastMessageId = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_id");
            final int _cursorIndexOfLastMessagePreview = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_preview");
            final int _cursorIndexOfLastMessageAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_message_at");
            final int _cursorIndexOfUnreadCount = CursorUtil.getColumnIndexOrThrow(_cursor, "unread_count");
            final int _cursorIndexOfPeerFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "peer_fingerprint");
            final int _cursorIndexOfCallId = CursorUtil.getColumnIndexOrThrow(_cursor, "call_id");
            final List<ConversationEntity> _result = new ArrayList<ConversationEntity>(_cursor.getCount());
            while (_cursor.moveToNext()) {
                final ConversationEntity _item;
                _item = new ConversationEntity();
                if (_cursor.isNull(_cursorIndexOfId)) { _item.id = null; } else { _item.id = _cursor.getString(_cursorIndexOfId); }
                if (_cursor.isNull(_cursorIndexOfType)) { _item.type = null; } else { _item.type = _cursor.getString(_cursorIndexOfType); }
                if (_cursor.isNull(_cursorIndexOfTitle)) { _item.title = null; } else { _item.title = _cursor.getString(_cursorIndexOfTitle); }
                _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
                _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
                if (_cursor.isNull(_cursorIndexOfLastMessageId)) { _item.lastMessageId = null; } else { _item.lastMessageId = _cursor.getString(_cursorIndexOfLastMessageId); }
                if (_cursor.isNull(_cursorIndexOfLastMessagePreview)) { _item.lastMessagePreview = null; } else { _item.lastMessagePreview = _cursor.getString(_cursorIndexOfLastMessagePreview); }
                _item.lastMessageAt = _cursor.getLong(_cursorIndexOfLastMessageAt);
                _item.unreadCount = _cursor.getInt(_cursorIndexOfUnreadCount);
                if (_cursor.isNull(_cursorIndexOfPeerFingerprint)) { _item.peerFingerprint = null; } else { _item.peerFingerprint = _cursor.getString(_cursorIndexOfPeerFingerprint); }
                if (_cursor.isNull(_cursorIndexOfCallId)) { _item.callId = null; } else { _item.callId = _cursor.getString(_cursorIndexOfCallId); }
                _result.add(_item);
            }
            return _result;
        } finally {
            _cursor.close();
            _statement.release();
        }
    }

    @Override
    public int count() {
        final String _sql = "SELECT COUNT(*) FROM conversations";
        final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
        __db.assertNotSuspendingTransaction();
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
            final int _result;
            if (_cursor.moveToFirst()) {
                _result = _cursor.getInt(0);
            } else {
                _result = 0;
            }
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