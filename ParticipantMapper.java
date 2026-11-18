package com.my.mealse.messenger.mapper;

import com.my.mealse.messenger.data.entity.ParticipantEntity;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.model.ParticipantRole;

public final class ParticipantMapper {

    private ParticipantMapper() {
    }

    public static Participant toModel(ParticipantEntity e) {
        if (e == null) return null;
        Participant p = new Participant();
        p.setId(e.id);
        p.setConversationId(e.conversationId);
        p.setNodeKey(e.nodeKey);
        p.setFingerprint(e.fingerprint);
        p.setDeviceId(e.deviceId);
        p.setDisplayName(e.displayName);
        p.setRole(ParticipantRole.fromString(e.role));
        p.setJoinedAt(e.joinedAt);
        return p;
    }

    public static ParticipantEntity toEntity(Participant p) {
        if (p == null) return null;
        ParticipantEntity e = new ParticipantEntity();
        e.id = p.getId();
        e.conversationId = p.getConversationId();
        e.nodeKey = p.getNodeKey();
        e.fingerprint = p.getFingerprint();
        e.deviceId = p.getDeviceId();
        e.displayName = p.getDisplayName();
        e.role = (p.getRole() != null ? p.getRole() : ParticipantRole.MEMBER).toStorageString();
        e.joinedAt = p.getJoinedAt();
        return e;
    }
}