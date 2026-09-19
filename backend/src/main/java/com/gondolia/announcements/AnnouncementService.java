package com.gondolia.announcements;

import com.gondolia.announcements.dto.AnnouncementDetailDto;
import com.gondolia.announcements.dto.AnnouncementListItem;
import com.gondolia.announcements.dto.CreateAnnouncementRequest;
import com.gondolia.announcements.dto.RecallPreviewRequest;
import com.gondolia.announcements.dto.RecallPreviewResponse;
import com.gondolia.announcements.dto.RecallRequest;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Barcodes;
import com.gondolia.common.util.LotNumbers;
import com.gondolia.domain.announcement.Announcement;
import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementReadRepository;
import com.gondolia.domain.announcement.AnnouncementRecallLot;
import com.gondolia.domain.announcement.AnnouncementRecallLotRepository;
import com.gondolia.domain.announcement.AnnouncementRepository;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.announcement.RecallMatchRepository;
import com.gondolia.domain.announcement.RecallMatchStatus;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avisos y recalls desde la consola de dueños (SPEC §6.7): alta con publicación inmediata, archivado, detalle con
 * estadísticas agregadas y vista previa del alcance de un recall.
 * <p>
 * Publicar reparte una notificación {@code ANNOUNCEMENT} a todos los usuarios de comercios {@code ACTIVE} (filtrando
 * por rubro solo en los avisos generales) y, si es un recall, dispara
 * {@link RecallMatchingService#matchAnnouncement(Long)}, que pone los lotes alcanzados en cuarentena y avisa a las
 * sucursales. Las respuestas nunca dicen <b>qué</b> comercios coinciden: solo cantidades (SPEC §3.4.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /** Link de la bandeja de avisos del comercio. */
    public static final String NOTICES_LINK = "/app/notices";
    public static final String REFERENCE_TYPE = "ANNOUNCEMENT";

    private static final int MAX_RECALL_LOTS = 50;
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecallLotRepository recallLotRepository;
    private final AnnouncementReadRepository readRepository;
    private final RecallMatchRepository recallMatchRepository;
    private final LotRepository lotRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RecallMatchingService recallMatchingService;
    private final AnnouncementMapper mapper;

    // ------------------------------------------------------------------ lectura

    /** Listado de la consola de dueños, más nuevos primero. */
    @Transactional(readOnly = true)
    public PageResponse<AnnouncementListItem> list(AnnouncementKind kind, AnnouncementStatus status, int page,
                                                   int size) {
        Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);
        Page<Announcement> found = announcementRepository.findAll(filter(kind, status), pageable);
        Map<Long, List<String>> lots =
                mapper.lotNumbersByAnnouncement(found.getContent().stream().map(Announcement::getId).toList());
        Map<Long, String> authors = authorNames(found.getContent());
        return PageResponse.of(found, announcement -> new AnnouncementListItem(
                announcement.getId(),
                announcement.getKind(),
                announcement.getSeverity(),
                announcement.getStatus(),
                announcement.getTitle(),
                announcement.getBody(),
                List.copyOf(announcement.targetBusinessTypeSet()),
                announcement.getPublishedAt(),
                announcement.getCreatedAt(),
                authors.get(announcement.getId()),
                announcement.getRecipientsCount(),
                announcement.getAffectedTenantsCount(),
                mapper.recall(announcement, lots.getOrDefault(announcement.getId(), List.of()))));
    }

    /** Detalle con estadísticas agregadas. */
    @Transactional(readOnly = true)
    public AnnouncementDetailDto detail(Long id) {
        return toDetail(require(id));
    }

    // ------------------------------------------------------------------ escritura

    /** Crea el aviso, lo publica y reparte las notificaciones (y el barrido de lotes si es un recall). */
    @Transactional
    public AnnouncementDetailDto create(CreateAnnouncementRequest request) {
        Announcement announcement = new Announcement();
        announcement.setKind(request.kind());
        announcement.setSeverity(defaultSeverity(request.kind(), request.severity()));
        announcement.setStatus(AnnouncementStatus.PUBLISHED);
        announcement.setTitle(request.title().strip());
        announcement.setBody(request.body().strip());
        announcement.setCreatedBy(CurrentUser.id());
        announcement.setPublishedAt(Timestamps.now());

        List<String> lotNumbers = List.of();
        if (request.kind() == AnnouncementKind.RECALL) {
            RecallRequest recall = validateRecall(request.recall());
            lotNumbers = cleanLotNumbers(recall.lotNumbers());
            if (!recall.allLots() && lotNumbers.isEmpty()) {
                throw new BadRequestException("VALIDATION_ERROR",
                        "Indicá al menos un número de lote o marcá que el recall alcanza a todos los lotes");
            }
            announcement.setRecallProductName(recall.productName().strip());
            announcement.setRecallBrand(blankToNull(recall.brand()));
            announcement.setRecallBarcode(Barcodes.normalize(recall.barcode()));
            announcement.setRecallAllLots(recall.allLots());
            announcement.setRecallExpiryFrom(recall.expiryFrom());
            announcement.setRecallExpiryTo(recall.expiryTo());
            announcement.setRecallReason(recall.reason().strip());
            announcement.setRecallInstructions(recall.instructions().strip());
            // Un retiro de seguridad alimentaria alcanza a todos los rubros.
            announcement.assignTargetBusinessTypes(null);
        } else {
            if (request.recall() != null) {
                throw new BadRequestException("VALIDATION_ERROR",
                        "Los datos de retiro son solo para los recalls: elegí el tipo Recall o sacá ese bloque");
            }
            announcement.assignTargetBusinessTypes(businessTypes(request.targetBusinessTypes()));
        }

        Announcement saved = announcementRepository.save(announcement);
        if (!lotNumbers.isEmpty()) {
            List<AnnouncementRecallLot> rows = new ArrayList<>();
            for (String raw : lotNumbers) {
                AnnouncementRecallLot lot = AnnouncementRecallLot.of(saved.getId(), raw);
                if (lot != null) {
                    rows.add(lot);
                }
            }
            recallLotRepository.saveAll(rows);
            recallLotRepository.flush();
        }

        int recipients = notificationService.notifyAllActiveTenants(
                new NotificationDraft(NotificationType.ANNOUNCEMENT, saved.getSeverity(), saved.getTitle(),
                        summary(saved.getBody()), NOTICES_LINK, REFERENCE_TYPE, saved.getId()),
                saved.isRecall() ? null : nullIfEmpty(saved.targetBusinessTypeSet()));
        saved.setRecipientsCount(recipients);

        if (saved.isRecall()) {
            recallMatchingService.matchAnnouncement(saved.getId());
        }
        log.info("Aviso {} publicado ({}): {} destinatarios", saved.getId(), saved.getKind(), recipients);
        return toDetail(saved);
    }

    /** Archiva un aviso: deja de verse en los comercios (las coincidencias abiertas siguen vivas). */
    @Transactional
    public AnnouncementDetailDto archive(Long id) {
        Announcement announcement = require(id);
        if (announcement.getStatus() == AnnouncementStatus.ARCHIVED) {
            throw new ConflictException("CONFLICT", "El aviso ya está archivado");
        }
        announcement.setStatus(AnnouncementStatus.ARCHIVED);
        return toDetail(announcement);
    }

    // ------------------------------------------------------------------ vista previa

    /**
     * Cuántos comercios y lotes con remanente alcanzaría el recall. Usa la misma regla de coincidencia que
     * {@link RecallMatchingService} pero sin efectos: no crea coincidencias ni pone lotes en cuarentena.
     */
    @Transactional(readOnly = true)
    public RecallPreviewResponse preview(RecallPreviewRequest request) {
        String barcode = Barcodes.normalize(request.barcode());
        if (barcode == null || !Barcodes.isValid(barcode)) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "El código de barras tiene que ser alfanumérico de hasta 32 caracteres");
        }
        validateExpiryRange(request.expiryFrom(), request.expiryTo());
        Set<String> lotNumbers = normalizedLots(request.lotNumbers());
        if (!request.allLots() && lotNumbers.isEmpty()) {
            return new RecallPreviewResponse(0, 0, 0);
        }
        List<Lot> matching = lotRepository.findStockedLotsByBarcodeInActiveTenants(barcode).stream()
                .filter(lot -> matches(lot, request.allLots(), lotNumbers, request.expiryFrom(), request.expiryTo()))
                .toList();
        int tenants = (int) matching.stream().map(Lot::getTenantId).distinct().count();
        int units = matching.stream().mapToInt(Lot::getQuantity).sum();
        return new RecallPreviewResponse(tenants, matching.size(), units);
    }

    /** Regla de coincidencia de SPEC §5.3, sin efectos. */
    private static boolean matches(Lot lot, boolean allLots, Set<String> lotNumbers, LocalDate from, LocalDate to) {
        String normalized = lot.getLotNumberNormalized() != null
                ? lot.getLotNumberNormalized()
                : LotNumbers.normalize(lot.getLotNumber());
        boolean lotMatches = allLots || (normalized != null && lotNumbers.contains(normalized));
        if (!lotMatches) {
            return false;
        }
        if (from == null && to == null) {
            return true;
        }
        LocalDate expiry = lot.getExpiryDate();
        if (expiry == null) {
            return false;
        }
        return (from == null || !expiry.isBefore(from)) && (to == null || !expiry.isAfter(to));
    }

    // ------------------------------------------------------------------ helpers

    private Announcement require(Long id) {
        return announcementRepository.findById(id).orElseThrow(() -> new NotFoundException("El aviso no existe"));
    }

    private AnnouncementDetailDto toDetail(Announcement announcement) {
        Long id = announcement.getId();
        boolean recall = announcement.isRecall();
        return new AnnouncementDetailDto(
                id,
                announcement.getKind(),
                announcement.getSeverity(),
                announcement.getStatus(),
                announcement.getTitle(),
                announcement.getBody(),
                List.copyOf(announcement.targetBusinessTypeSet()),
                announcement.getPublishedAt(),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt(),
                authorNames(List.of(announcement)).get(id),
                mapper.recall(announcement, recall ? mapper.lotNumbers(id) : List.of()),
                announcement.getRecipientsCount(),
                readRepository.countByAnnouncementId(id),
                announcement.getAffectedTenantsCount(),
                recall ? recallMatchRepository.countByAnnouncementIdAndStatus(id, RecallMatchStatus.OPEN) : 0,
                recall ? recallMatchRepository.countByAnnouncementIdAndStatus(id, RecallMatchStatus.ACKNOWLEDGED) : 0,
                recall ? recallMatchRepository.countByAnnouncementIdAndStatus(id, RecallMatchStatus.RESOLVED) : 0);
    }

    private Map<Long, String> authorNames(Collection<Announcement> announcements) {
        List<Long> userIds = announcements.stream()
                .map(Announcement::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> byUser = new java.util.HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            byUser.put(user.getId(), user.getFullName());
        }
        Map<Long, String> byAnnouncement = new java.util.HashMap<>();
        for (Announcement announcement : announcements) {
            String name = announcement.getCreatedBy() == null ? null : byUser.get(announcement.getCreatedBy());
            if (name != null) {
                byAnnouncement.put(announcement.getId(), name);
            }
        }
        return byAnnouncement;
    }

    private static Specification<Announcement> filter(AnnouncementKind kind, AnnouncementStatus status) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (kind != null) {
                predicates.add(builder.equal(root.get("kind"), kind));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return predicates.isEmpty() ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private RecallRequest validateRecall(RecallRequest recall) {
        if (recall == null) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "Cargá los datos del producto retirado para publicar un recall");
        }
        String barcode = Barcodes.normalize(recall.barcode());
        if (barcode == null || !Barcodes.isValid(barcode)) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "El código de barras tiene que ser alfanumérico de hasta 32 caracteres");
        }
        if (barcode.chars().allMatch(Character::isDigit) && !Barcodes.isValidGtin(barcode)) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "El dígito verificador del código de barras no es correcto: revisá los números");
        }
        validateExpiryRange(recall.expiryFrom(), recall.expiryTo());
        if (recall.lotNumbers() != null && recall.lotNumbers().size() > MAX_RECALL_LOTS) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "No podés cargar más de " + MAX_RECALL_LOTS + " lotes en un recall");
        }
        return recall;
    }

    private static void validateExpiryRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("VALIDATION_ERROR",
                    "El rango de vencimiento está invertido: la fecha desde tiene que ser anterior a la fecha hasta");
        }
    }

    /** Lotes tal como los escribió el dueño, sin repetidos ni vacíos (se normalizan al guardarlos). */
    private static List<String> cleanLotNumbers(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : raw) {
            String normalized = LotNumbers.normalize(value);
            if (normalized != null && seen.add(normalized)) {
                result.add(LotNumbers.clean(value));
            }
        }
        return result;
    }

    private static Set<String> normalizedLots(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                String normalized = LotNumbers.normalize(value);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    private static Set<BusinessType> businessTypes(List<BusinessType> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<BusinessType> types = EnumSet.noneOf(BusinessType.class);
        types.addAll(raw);
        return types.size() == BusinessType.values().length ? Set.of() : types;
    }

    private static Set<BusinessType> nullIfEmpty(Set<BusinessType> types) {
        return types == null || types.isEmpty() ? null : types;
    }

    private static Severity defaultSeverity(AnnouncementKind kind, Severity severity) {
        if (severity != null) {
            return severity;
        }
        return kind == AnnouncementKind.RECALL ? Severity.CRITICAL : Severity.INFO;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Cuerpo recortado para la notificación (el aviso completo se lee en la pantalla de Avisos). */
    private static String summary(String body) {
        return Optional.ofNullable(body)
                .map(String::strip)
                .map(text -> text.length() <= 300 ? text : text.substring(0, 297).strip() + "…")
                .orElse(null);
    }
}
