package com.my.mealse.messenger.repo;

import android.content.Context;

import androidx.annotation.Nullable;

import com.my.mealse.messenger.data.DatabaseHolder;
import com.my.mealse.messenger.data.dao.ParticipantDao;
import com.my.mealse.messenger.data.entity.ParticipantEntity;
import com.my.mealse.messenger.mapper.ParticipantMapper;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.model.ParticipantRole;
import com.my.mealse.util.MealseLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Репозиторий участников диалога. Синхронные Room-операции.
 */
public class ParticipantRepository {

    private static final String TAG = "MessengerPartRepo";

    private final ParticipantDao dao;
    private final MealseLogger log;

    public ParticipantRepository(Context context) {
        Context appCtx = context.getApplicationContext();
        this.dao = DatabaseHolder.get(appCtx).participantDao();
        this.log = MealseLogger.get(appCtx, TAG);
    }

    /**
     * Добавляет участника. Если такой fingerprint уже есть в диалоге — replace.
     */
    public Participant add(String conversationId, String nodeKey,
                           String fingerprint, String deviceId,
                           String displayName, ParticipantRole role) {
        if (conversationId == null) return null;

        long now = System.currentTimeMillis();
        ParticipantEntity e = new ParticipantEntity();
        e.id = UUID.randomUUID().toString();
        e.conversationId = conversationId;
        e.nodeKey = nodeKey;
        e.fingerprint = fingerprint;
        e.deviceId = deviceId;
        e.displayName = displayName;
        e.role = (role != null ? role : ParticipantRole.MEMBER).toStorageString();
        e.joinedAt = now;

        dao.insert(e);
        log.log("add: conv=" + conversationId + ", fp=" + fingerprint
            + ", name=" + displayName);
        return ParticipantMapper.toModel(e);
    }

    public void addAll(List<Participant> participants) {
        if (participants == null || participants.isEmpty()) return;
        List<ParticipantEntity> entities = new ArrayList<>(participants.size());
        for (Participant p : participants) {
            entities.add(ParticipantMapper.toEntity(p));
        }
        dao.insertAll(entities);
    }

    public List<Participant> getByConversation(String conversationId) {
        if (conversationId == null) return new ArrayList<>();
        List<ParticipantEntity> entities = dao.getByConversation(conversationId);
        List<Participant> result = new ArrayList<>(entities.size());
        for (ParticipantEntity e : entities) {
            result.add(ParticipantMapper.toModel(e));
        }
        return result;
    }

    @Nullable
    public Participant findByFingerprint(String conversationId, String fingerprint) {
        if (conversationId == null || fingerprint == null) return null;
        return ParticipantMapper.toModel(dao.findByFingerprint(conversationId, fingerprint));
    }

    public int countByConversation(String conversationId) {
        if (conversationId == null) return 0;
        return dao.countByConversation(conversationId);
    }

    public void deleteByConversation(String conversationId) {
        if (conversationId == null) return;
        dao.deleteByConversation(conversationId);
    }

    public void deleteById(String id) {
        if (id == null) return;
        dao.deleteById(id);
    }
}