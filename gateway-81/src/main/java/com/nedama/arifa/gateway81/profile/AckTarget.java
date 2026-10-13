package com.nedama.arifa.gateway81.profile;

import java.util.List;
import java.util.UUID;

import com.inductiveautomation.ignition.common.QualifiedPath;

/**
 * What one acknowledgement code stands for: who was notified, and which alarm events the SMS was about.
 * Kept in memory by the registry until the operator replies or the code expires.
 *
 * @param userPath  the notified user, recorded as the person who acknowledged
 * @param userLabel the same user as text, for logs
 * @param eventIds  every alarm event in the notification (several when the notification was consolidated)
 */
public record AckTarget(QualifiedPath userPath, String userLabel, List<UUID> eventIds) {

    public AckTarget {
        eventIds = List.copyOf(eventIds);
    }
}
