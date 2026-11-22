package com.my.mealse.messenger.data.dao;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.my.mealse.messenger.data.entity.MessageEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.my.mealse.messenger.model.MessageStatus;

public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;
  private final EntityInsertionAdapter<MessageEntity> __insertionAdapterOfMessageEntity;
  private final SharedSQLiteStatement __preparedStmtOfUpdateStatusDelivered;
  private final SharedSQLiteStatement __preparedStmtOfUpdateStatusRead;
  private final SharedSQLiteStatement __preparedStmtOfUpdateStatus;
  private final SharedSQLiteStatement __preparedStmtOfUpdateStatusIfCurrent;
  private final SharedSQLiteStatement __preparedStmtOfDeleteById;
  private final SharedSQLiteStatement __preparedStmtOfDeleteByConversation;
  private final SharedSQLiteStatement __preparedStmtOfDeleteAll;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessageEntity = new EntityInsertionAdapter<MessageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `messages` (`id`,`conversation_id`,`sender_fingerprint`,`sender_device_id`,`sender_node_key`,`type`,`body`,`encrypted_body`,`attachment_uri`,`attachment_name`,`attachment_mime`,`attachment_size`,`created_at`,`status`,`delivered_at`,`read_at`,`reply_to_message_id`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final MessageEntity entity) {
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
        if (entity.senderFingerprint == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.senderFingerprint);
        }
        if (entity.senderDeviceId == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.senderDeviceId);
        }
        if (entity.senderNodeKey == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.senderNodeKey);
        }
        if (entity.type == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.type);
        }
        if (entity.body == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.body);
        }
        if (entity.encryptedBody == null) {
          statement.bindNull(8);
        } else {
          statement.bindBlob(8, entity.encryptedBody);
        }
        if (entity.attachmentUri == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.attachmentUri);
        }
        if (entity.attachmentName == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.attachmentName);
        }
        if (entity.attachmentMime == null) {
          statement.bindNull(11);
        } else {
          statement.bindString(11, entity.attachmentMime);
        }
        statement.bindLong(12, entity.attachmentSize);
        statement.bindLong(13, entity.createdAt);
        if (entity.status == null) {
          statement.bindNull(14);
        } else {
          statement.bindString(14, entity.status);
        }
        statement.bindLong(15, entity.deliveredAt);
        statement.bindLong(16, entity.readAt);
        if (entity.replyToMessageId == null) {
          statement.bindNull(17);
        } else {
          statement.bindString(17, entity.replyToMessageId);
        }
      }
    };
    this.__preparedStmtOfUpdateStatusDelivered = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "UPDATE messages SET status = ?, delivered_at = ? WHERE id = ?";
      }
    };
    this.__preparedStmtOfUpdateStatusRead = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "UPDATE messages SET status = ?, read_at = ? WHERE id = ?";
      }
    };
    this.__preparedStmtOfUpdateStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "UPDATE messages SET status = ? WHERE id = ?";
      }
    };
    this.__preparedStmtOfUpdateStatusIfCurrent = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "UPDATE messages SET status = ? WHERE id = ? AND status = ?";
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "DELETE FROM messages WHERE id = ?";
      }
    };
    this.__preparedStmtOfDeleteByConversation = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "DELETE FROM messages WHERE conversation_id = ?";
      }
    };
    this.__preparedStmtOfDeleteAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        return "DELETE FROM messages";
      }
    };
  }

  @Override
  public long insert(final MessageEntity entity) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      long _result = __insertionAdapterOfMessageEntity.insertAndReturnId(entity);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public MessageEntity getById(final String id) {
    final String _sql = "SELECT * FROM messages WHERE id = ? LIMIT 1";
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
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "conversation_id");
      final int _cursorIndexOfSenderFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_fingerprint");
      final int _cursorIndexOfSenderDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_device_id");
      final int _cursorIndexOfSenderNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_node_key");
      final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
      final int _cursorIndexOfBody = CursorUtil.getColumnIndexOrThrow(_cursor, "body");
      final int _cursorIndexOfEncryptedBody = CursorUtil.getColumnIndexOrThrow(_cursor,
          "encrypted_body");
      final int _cursorIndexOfAttachmentUri = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_uri");
      final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_name");
      final int _cursorIndexOfAttachmentMime = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_mime");
      final int _cursorIndexOfAttachmentSize = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_size");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor,
          "delivered_at");
      final int _cursorIndexOfReadAt = CursorUtil.getColumnIndexOrThrow(_cursor, "read_at");
      final int _cursorIndexOfReplyToMessageId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "reply_to_message_id");
      final MessageEntity _result;
      if (_cursor.moveToFirst()) {
        _result = new MessageEntity();
        if (_cursor.isNull(_cursorIndexOfId)) {
          _result.id = null;
        } else {
          _result.id = _cursor.getString(_cursorIndexOfId);
        }
        if (_cursor.isNull(_cursorIndexOfConversationId)) {
          _result.conversationId = null;
        } else {
          _result.conversationId = _cursor.getString(_cursorIndexOfConversationId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderFingerprint)) {
          _result.senderFingerprint = null;
        } else {
          _result.senderFingerprint = _cursor.getString(_cursorIndexOfSenderFingerprint);
        }
        if (_cursor.isNull(_cursorIndexOfSenderDeviceId)) {
          _result.senderDeviceId = null;
        } else {
          _result.senderDeviceId = _cursor.getString(_cursorIndexOfSenderDeviceId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderNodeKey)) {
          _result.senderNodeKey = null;
        } else {
          _result.senderNodeKey = _cursor.getString(_cursorIndexOfSenderNodeKey);
        }
        if (_cursor.isNull(_cursorIndexOfType)) {
          _result.type = null;
        } else {
          _result.type = _cursor.getString(_cursorIndexOfType);
        }
        if (_cursor.isNull(_cursorIndexOfBody)) {
          _result.body = null;
        } else {
          _result.body = _cursor.getString(_cursorIndexOfBody);
        }
        if (_cursor.isNull(_cursorIndexOfEncryptedBody)) {
          _result.encryptedBody = null;
        } else {
          _result.encryptedBody = _cursor.getBlob(_cursorIndexOfEncryptedBody);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentUri)) {
          _result.attachmentUri = null;
        } else {
          _result.attachmentUri = _cursor.getString(_cursorIndexOfAttachmentUri);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
          _result.attachmentName = null;
        } else {
          _result.attachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentMime)) {
          _result.attachmentMime = null;
        } else {
          _result.attachmentMime = _cursor.getString(_cursorIndexOfAttachmentMime);
        }
        _result.attachmentSize = _cursor.getLong(_cursorIndexOfAttachmentSize);
        _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _result.status = null;
        } else {
          _result.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _result.deliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
        _result.readAt = _cursor.getLong(_cursorIndexOfReadAt);
        if (_cursor.isNull(_cursorIndexOfReplyToMessageId)) {
          _result.replyToMessageId = null;
        } else {
          _result.replyToMessageId = _cursor.getString(_cursorIndexOfReplyToMessageId);
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
  public List<MessageEntity> getByConversation(final String conversationId, final int limit,
      final int offset) {
    final String _sql = "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 3);
    int _argIndex = 1;
    if (conversationId == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, conversationId);
    }
    _argIndex = 2;
    _statement.bindLong(_argIndex, limit);
    _argIndex = 3;
    _statement.bindLong(_argIndex, offset);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "conversation_id");
      final int _cursorIndexOfSenderFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_fingerprint");
      final int _cursorIndexOfSenderDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_device_id");
      final int _cursorIndexOfSenderNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_node_key");
      final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
      final int _cursorIndexOfBody = CursorUtil.getColumnIndexOrThrow(_cursor, "body");
      final int _cursorIndexOfEncryptedBody = CursorUtil.getColumnIndexOrThrow(_cursor,
          "encrypted_body");
      final int _cursorIndexOfAttachmentUri = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_uri");
      final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_name");
      final int _cursorIndexOfAttachmentMime = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_mime");
      final int _cursorIndexOfAttachmentSize = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_size");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor,
          "delivered_at");
      final int _cursorIndexOfReadAt = CursorUtil.getColumnIndexOrThrow(_cursor, "read_at");
      final int _cursorIndexOfReplyToMessageId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "reply_to_message_id");
      final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final MessageEntity _item;
        _item = new MessageEntity();
        if (_cursor.isNull(_cursorIndexOfId)) {
          _item.id = null;
        } else {
          _item.id = _cursor.getString(_cursorIndexOfId);
        }
        if (_cursor.isNull(_cursorIndexOfConversationId)) {
          _item.conversationId = null;
        } else {
          _item.conversationId = _cursor.getString(_cursorIndexOfConversationId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderFingerprint)) {
          _item.senderFingerprint = null;
        } else {
          _item.senderFingerprint = _cursor.getString(_cursorIndexOfSenderFingerprint);
        }
        if (_cursor.isNull(_cursorIndexOfSenderDeviceId)) {
          _item.senderDeviceId = null;
        } else {
          _item.senderDeviceId = _cursor.getString(_cursorIndexOfSenderDeviceId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderNodeKey)) {
          _item.senderNodeKey = null;
        } else {
          _item.senderNodeKey = _cursor.getString(_cursorIndexOfSenderNodeKey);
        }
        if (_cursor.isNull(_cursorIndexOfType)) {
          _item.type = null;
        } else {
          _item.type = _cursor.getString(_cursorIndexOfType);
        }
        if (_cursor.isNull(_cursorIndexOfBody)) {
          _item.body = null;
        } else {
          _item.body = _cursor.getString(_cursorIndexOfBody);
        }
        if (_cursor.isNull(_cursorIndexOfEncryptedBody)) {
          _item.encryptedBody = null;
        } else {
          _item.encryptedBody = _cursor.getBlob(_cursorIndexOfEncryptedBody);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentUri)) {
          _item.attachmentUri = null;
        } else {
          _item.attachmentUri = _cursor.getString(_cursorIndexOfAttachmentUri);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
          _item.attachmentName = null;
        } else {
          _item.attachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentMime)) {
          _item.attachmentMime = null;
        } else {
          _item.attachmentMime = _cursor.getString(_cursorIndexOfAttachmentMime);
        }
        _item.attachmentSize = _cursor.getLong(_cursorIndexOfAttachmentSize);
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _item.status = null;
        } else {
          _item.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _item.deliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
        _item.readAt = _cursor.getLong(_cursorIndexOfReadAt);
        if (_cursor.isNull(_cursorIndexOfReplyToMessageId)) {
          _item.replyToMessageId = null;
        } else {
          _item.replyToMessageId = _cursor.getString(_cursorIndexOfReplyToMessageId);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<MessageEntity> getAllByConversation(final String conversationId) {
    final String _sql = "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC";
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
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "conversation_id");
      final int _cursorIndexOfSenderFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_fingerprint");
      final int _cursorIndexOfSenderDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_device_id");
      final int _cursorIndexOfSenderNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_node_key");
      final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
      final int _cursorIndexOfBody = CursorUtil.getColumnIndexOrThrow(_cursor, "body");
      final int _cursorIndexOfEncryptedBody = CursorUtil.getColumnIndexOrThrow(_cursor,
          "encrypted_body");
      final int _cursorIndexOfAttachmentUri = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_uri");
      final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_name");
      final int _cursorIndexOfAttachmentMime = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_mime");
      final int _cursorIndexOfAttachmentSize = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_size");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor,
          "delivered_at");
      final int _cursorIndexOfReadAt = CursorUtil.getColumnIndexOrThrow(_cursor, "read_at");
      final int _cursorIndexOfReplyToMessageId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "reply_to_message_id");
      final List<MessageEntity> _result = new ArrayList<MessageEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final MessageEntity _item;
        _item = new MessageEntity();
        if (_cursor.isNull(_cursorIndexOfId)) {
          _item.id = null;
        } else {
          _item.id = _cursor.getString(_cursorIndexOfId);
        }
        if (_cursor.isNull(_cursorIndexOfConversationId)) {
          _item.conversationId = null;
        } else {
          _item.conversationId = _cursor.getString(_cursorIndexOfConversationId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderFingerprint)) {
          _item.senderFingerprint = null;
        } else {
          _item.senderFingerprint = _cursor.getString(_cursorIndexOfSenderFingerprint);
        }
        if (_cursor.isNull(_cursorIndexOfSenderDeviceId)) {
          _item.senderDeviceId = null;
        } else {
          _item.senderDeviceId = _cursor.getString(_cursorIndexOfSenderDeviceId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderNodeKey)) {
          _item.senderNodeKey = null;
        } else {
          _item.senderNodeKey = _cursor.getString(_cursorIndexOfSenderNodeKey);
        }
        if (_cursor.isNull(_cursorIndexOfType)) {
          _item.type = null;
        } else {
          _item.type = _cursor.getString(_cursorIndexOfType);
        }
        if (_cursor.isNull(_cursorIndexOfBody)) {
          _item.body = null;
        } else {
          _item.body = _cursor.getString(_cursorIndexOfBody);
        }
        if (_cursor.isNull(_cursorIndexOfEncryptedBody)) {
          _item.encryptedBody = null;
        } else {
          _item.encryptedBody = _cursor.getBlob(_cursorIndexOfEncryptedBody);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentUri)) {
          _item.attachmentUri = null;
        } else {
          _item.attachmentUri = _cursor.getString(_cursorIndexOfAttachmentUri);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
          _item.attachmentName = null;
        } else {
          _item.attachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentMime)) {
          _item.attachmentMime = null;
        } else {
          _item.attachmentMime = _cursor.getString(_cursorIndexOfAttachmentMime);
        }
        _item.attachmentSize = _cursor.getLong(_cursorIndexOfAttachmentSize);
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _item.status = null;
        } else {
          _item.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _item.deliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
        _item.readAt = _cursor.getLong(_cursorIndexOfReadAt);
        if (_cursor.isNull(_cursorIndexOfReplyToMessageId)) {
          _item.replyToMessageId = null;
        } else {
          _item.replyToMessageId = _cursor.getString(_cursorIndexOfReplyToMessageId);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public MessageEntity getLastByConversation(final String conversationId) {
    final String _sql = "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1";
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
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "conversation_id");
      final int _cursorIndexOfSenderFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_fingerprint");
      final int _cursorIndexOfSenderDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_device_id");
      final int _cursorIndexOfSenderNodeKey = CursorUtil.getColumnIndexOrThrow(_cursor,
          "sender_node_key");
      final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
      final int _cursorIndexOfBody = CursorUtil.getColumnIndexOrThrow(_cursor, "body");
      final int _cursorIndexOfEncryptedBody = CursorUtil.getColumnIndexOrThrow(_cursor,
          "encrypted_body");
      final int _cursorIndexOfAttachmentUri = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_uri");
      final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_name");
      final int _cursorIndexOfAttachmentMime = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_mime");
      final int _cursorIndexOfAttachmentSize = CursorUtil.getColumnIndexOrThrow(_cursor,
          "attachment_size");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor,
          "delivered_at");
      final int _cursorIndexOfReadAt = CursorUtil.getColumnIndexOrThrow(_cursor, "read_at");
      final int _cursorIndexOfReplyToMessageId = CursorUtil.getColumnIndexOrThrow(_cursor,
          "reply_to_message_id");
      final MessageEntity _result;
      if (_cursor.moveToFirst()) {
        _result = new MessageEntity();
        if (_cursor.isNull(_cursorIndexOfId)) {
          _result.id = null;
        } else {
          _result.id = _cursor.getString(_cursorIndexOfId);
        }
        if (_cursor.isNull(_cursorIndexOfConversationId)) {
          _result.conversationId = null;
        } else {
          _result.conversationId = _cursor.getString(_cursorIndexOfConversationId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderFingerprint)) {
          _result.senderFingerprint = null;
        } else {
          _result.senderFingerprint = _cursor.getString(_cursorIndexOfSenderFingerprint);
        }
        if (_cursor.isNull(_cursorIndexOfSenderDeviceId)) {
          _result.senderDeviceId = null;
        } else {
          _result.senderDeviceId = _cursor.getString(_cursorIndexOfSenderDeviceId);
        }
        if (_cursor.isNull(_cursorIndexOfSenderNodeKey)) {
          _result.senderNodeKey = null;
        } else {
          _result.senderNodeKey = _cursor.getString(_cursorIndexOfSenderNodeKey);
        }
        if (_cursor.isNull(_cursorIndexOfType)) {
          _result.type = null;
        } else {
          _result.type = _cursor.getString(_cursorIndexOfType);
        }
        if (_cursor.isNull(_cursorIndexOfBody)) {
          _result.body = null;
        } else {
          _result.body = _cursor.getString(_cursorIndexOfBody);
        }
        if (_cursor.isNull(_cursorIndexOfEncryptedBody)) {
          _result.encryptedBody = null;
        } else {
          _result.encryptedBody = _cursor.getBlob(_cursorIndexOfEncryptedBody);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentUri)) {
          _result.attachmentUri = null;
        } else {
          _result.attachmentUri = _cursor.getString(_cursorIndexOfAttachmentUri);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
          _result.attachmentName = null;
        } else {
          _result.attachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
        }
        if (_cursor.isNull(_cursorIndexOfAttachmentMime)) {
          _result.attachmentMime = null;
        } else {
          _result.attachmentMime = _cursor.getString(_cursorIndexOfAttachmentMime);
        }
        _result.attachmentSize = _cursor.getLong(_cursorIndexOfAttachmentSize);
        _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _result.status = null;
        } else {
          _result.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _result.deliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
        _result.readAt = _cursor.getLong(_cursorIndexOfReadAt);
        if (_cursor.isNull(_cursorIndexOfReplyToMessageId)) {
          _result.replyToMessageId = null;
        } else {
          _result.replyToMessageId = _cursor.getString(_cursorIndexOfReplyToMessageId);
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
  public void updateStatusDelivered(final String id, final String status, final long ts) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatusDelivered.acquire();
    int _argIndex = 1;
    if (status == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, status);
    }
    _argIndex = 2;
    _stmt.bindLong(_argIndex, ts);
    _argIndex = 3;
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
      __preparedStmtOfUpdateStatusDelivered.release(_stmt);
    }
  }

  @Override
  public void updateStatusRead(final String id, final String status, final long ts) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatusRead.acquire();
    int _argIndex = 1;
    if (status == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, status);
    }
    _argIndex = 2;
    _stmt.bindLong(_argIndex, ts);
    _argIndex = 3;
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
      __preparedStmtOfUpdateStatusRead.release(_stmt);
    }
  }

  @Override
  public void updateStatus(final String id, final String status) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatus.acquire();
    int _argIndex = 1;
    if (status == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, status);
    }
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
      __preparedStmtOfUpdateStatus.release(_stmt);
    }
  }

  @Override
  public void updateStatusIfCurrent(final String id, final String status,
      final String expectedCurrent) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatusIfCurrent.acquire();
    int _argIndex = 1;
    if (status == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, status);
    }
    _argIndex = 2;
    if (id == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, id);
    }
    _argIndex = 3;
    if (expectedCurrent == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, expectedCurrent);
    }
    __db.beginTransaction();
    try {
      _stmt.executeUpdateDelete();
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
      __preparedStmtOfUpdateStatusIfCurrent.release(_stmt);
    }
  }

  @Override
  public int countByStatus(final String conversationId, final String status) {
    final String _sql = "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND status = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    if (conversationId == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, conversationId);
    }
    _argIndex = 2;
    if (status == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, status);
    }
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
  public int exists(final String id) {
    final String _sql = "SELECT COUNT(*) FROM messages WHERE id = ?";
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

  @Override
  public List<String> selectUnreadIncomingIds(final String convId, final String selfFp) {
    final String _sql = "SELECT id FROM messages WHERE conversation_id = ? "
        + "AND sender_fingerprint != ? AND status IN (?, ?)";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 4);
    int _argIndex = 1;
    if (convId == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, convId);
    }
    _argIndex = 2;
    if (selfFp == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, selfFp);
    }
    _argIndex = 3;
    _statement.bindString(_argIndex, MessageStatus.SENT.toStorageString());
    _argIndex = 4;
    _statement.bindString(_argIndex, MessageStatus.DELIVERED.toStorageString());
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final List<String> _result = new ArrayList<String>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final String _item;
        if (_cursor.isNull(0)) {
          _item = null;
        } else {
          _item = _cursor.getString(0);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int markReadByIds(final List<String> ids, final long readAt) {
    if (ids == null || ids.isEmpty())
      return 0;
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      int _total = 0;
      // ★ Батчи по 500: лимит числа host-параметров SQLite
      // (на части устройств 999). Для чата с 10 000 сообщений
      // один огромный IN (...) может упасть — режем на куски.
      final int _batch = 500;
      for (int _start = 0; _start < ids.size(); _start += _batch) {
        final int _end = Math.min(_start + _batch, ids.size());
        final int _n = _end - _start;
        final StringBuilder _sb = new StringBuilder(
            "UPDATE messages SET status = ?, read_at = ? WHERE id IN (");
        for (int i = 0; i < _n; i++) {
          if (i > 0)
            _sb.append(',');
          _sb.append('?');
        }
        _sb.append(')');

        final SupportSQLiteStatement _stmt = __db.compileStatement(_sb.toString());
        try {
          _stmt.bindString(1, MessageStatus.READ.toStorageString());
          _stmt.bindLong(2, readAt);
          int _argIndex = 3;
          for (int i = _start; i < _end; i++) {
            final String _item = ids.get(i);
            if (_item == null) {
              _stmt.bindNull(_argIndex);
            } else {
              _stmt.bindString(_argIndex, _item);
            }
            _argIndex++;
          }
          _total += _stmt.executeUpdateDelete();
        } finally {
          // ★ close() у SupportSQLiteStatement бросает IOException
          // (наследует java.io.Closeable) — гасим локально.
          try {
            _stmt.close();
          } catch (java.io.IOException ignored) {
          }
        }
      }
      __db.setTransactionSuccessful();
      return _total;
    } finally {
      __db.endTransaction();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}