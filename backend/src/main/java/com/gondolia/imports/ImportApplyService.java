package com.gondolia.imports;

import com.gondolia.domain.imports.ImportJob;
import com.gondolia.domain.imports.ImportJobRepository;
import com.gondolia.domain.imports.ImportStatus;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Aplicación asincrónica de una importación (SPEC §16.3): recorre las filas válidas en bloques de 200, cada bloque
 * en su propia transacción, y va actualizando {@code processedRows} para la barra de progreso.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportApplyService {

    private final ImportChunkProcessor processor;
    private final ImportJobRepository jobRepository;
    private final ImportRowStore rowStore;

    /** Importaciones para las que se pidió cortar la aplicación en curso. */
    private final Set<Long> cancelRequests = ConcurrentHashMap.newKeySet();

    /** Pide cortar una aplicación en curso: termina al cerrar el bloque que esté procesando. */
    public void requestCancel(Long jobId) {
        cancelRequests.add(jobId);
    }

    @Async
    public void runAsync(Long jobId, Long tenantId, Long userId, List<BranchRef> branches) {
        try {
            run(jobId, tenantId, userId, branches);
        } catch (RuntimeException e) {
            log.error("Falló la importación {} del comercio {}", jobId, tenantId, e);
            processor.finish(jobId, ImportStatus.FAILED, null,
                    "No pudimos terminar la importación. Probá de nuevo o contactá a soporte.", userId);
        } finally {
            cancelRequests.remove(jobId);
        }
    }

    void run(Long jobId, Long tenantId, Long userId, List<BranchRef> branches) {
        ImportChunkProcessor.Totals totals = new ImportChunkProcessor.Totals();
        int lastRowNumber = 0;
        while (true) {
            if (cancelRequests.remove(jobId)) {
                log.info("Importación {} cancelada a pedido del usuario", jobId);
                processor.finish(jobId, ImportStatus.CANCELLED, totals.toResult(),
                        "Cancelaste la importación: las filas ya aplicadas quedan cargadas.", userId);
                return;
            }
            List<ImportRowStore.StoredRow> chunk =
                    rowStore.applicableRows(jobId, lastRowNumber, ImportService.APPLY_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                break;
            }
            lastRowNumber = chunk.getLast().rowNumber();
            processor.processChunk(jobId, tenantId, userId, branches, chunk, totals);
        }
        processor.finish(jobId, ImportStatus.APPLIED, totals.toResult(), null, userId);
        log.info("Importación {} aplicada: {} productos nuevos, {} actualizados, {} lotes", jobId,
                totals.productsCreated, totals.productsUpdated, totals.lotsCreated);
    }

    /** Estado actual del job (para las pruebas y el diagnóstico). */
    public ImportStatus statusOf(Long jobId) {
        return jobRepository.findById(jobId).map(ImportJob::getStatus).orElse(null);
    }

    /** Resultado ya guardado (para las pruebas). */
    public ImportDtos.ResultDto resultOf(Long jobId) {
        return jobRepository.findById(jobId)
                .map(job -> rowStore.fromNode(job.getResult(), ImportDtos.ResultDto.class))
                .orElse(null);
    }
}
