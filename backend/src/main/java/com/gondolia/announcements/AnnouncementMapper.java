package com.gondolia.announcements;

import com.gondolia.announcements.dto.RecallDetailDto;
import com.gondolia.domain.announcement.Announcement;
import com.gondolia.domain.announcement.AnnouncementRecallLot;
import com.gondolia.domain.announcement.AnnouncementRecallLotRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Armado del bloque {@code recall} de un aviso (los lotes viven en otra tabla) para las respuestas de la consola de
 * dueños y de los comercios.
 */
@Component
@RequiredArgsConstructor
public class AnnouncementMapper {

    private final AnnouncementRecallLotRepository recallLotRepository;

    /** Números de lote de cada recall, en el orden en que se cargaron. */
    public Map<Long, List<String>> lotNumbersByAnnouncement(Collection<Long> announcementIds) {
        if (announcementIds == null || announcementIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (AnnouncementRecallLot lot : recallLotRepository.findByAnnouncementIdIn(announcementIds)) {
            result.computeIfAbsent(lot.getAnnouncementId(), id -> new java.util.ArrayList<>()).add(lot.getLotNumber());
        }
        return result;
    }

    /** Lotes de un solo recall. */
    public List<String> lotNumbers(Long announcementId) {
        return recallLotRepository.findByAnnouncementIdOrderByIdAsc(announcementId).stream()
                .map(AnnouncementRecallLot::getLotNumber)
                .toList();
    }

    /** {@code null} si el aviso no es un recall. */
    public RecallDetailDto recall(Announcement announcement, List<String> lotNumbers) {
        if (!announcement.isRecall()) {
            return null;
        }
        return new RecallDetailDto(
                announcement.getRecallProductName(),
                announcement.getRecallBrand(),
                announcement.getRecallBarcode(),
                lotNumbers == null ? List.of() : List.copyOf(lotNumbers),
                announcement.isRecallAllLots(),
                announcement.getRecallExpiryFrom(),
                announcement.getRecallExpiryTo(),
                announcement.getRecallReason(),
                announcement.getRecallInstructions());
    }
}
