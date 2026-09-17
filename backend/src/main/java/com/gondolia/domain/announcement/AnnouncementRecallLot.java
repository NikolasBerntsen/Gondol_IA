package com.gondolia.domain.announcement;

import com.gondolia.common.util.LotNumbers;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "announcement_recall_lots")
@Getter
@Setter
@NoArgsConstructor
public class AnnouncementRecallLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long announcementId;

    private String lotNumber;

    private String lotNumberNormalized;

    /**
     * Crea el lote de recall con su forma normalizada; {@code null} si el número no tiene caracteres válidos.
     */
    public static AnnouncementRecallLot of(Long announcementId, String rawLotNumber) {
        String normalized = LotNumbers.normalize(rawLotNumber);
        if (normalized == null) {
            return null;
        }
        AnnouncementRecallLot lot = new AnnouncementRecallLot();
        lot.setAnnouncementId(announcementId);
        lot.setLotNumber(LotNumbers.clean(rawLotNumber));
        lot.setLotNumberNormalized(normalized);
        return lot;
    }
}
