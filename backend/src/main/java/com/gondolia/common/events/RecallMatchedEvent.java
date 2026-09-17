package com.gondolia.common.events;

import java.util.List;

/**
 * Un recall coincidió con lotes de un tenant; {@code matchIds} son los {@code recall_matches} nuevos.
 */
public record RecallMatchedEvent(Long tenantId, Long announcementId, List<Long> matchIds) {

    public RecallMatchedEvent {
        matchIds = matchIds == null ? List.of() : List.copyOf(matchIds);
    }
}
