package com.gondolia.pos.dto;

import java.util.List;

/**
 * Recall publicado que alcanza al código de barras del producto (SPEC §6.7). Los lotes cargados en la sucursal ya
 * pasaron por el chequeo de recall, así que se pueden vender; lo que el POS no deja vender son unidades sin lote
 * registrado (faltante), porque no hay forma de saber si son del lote retirado.
 */
public record PosRecallRef(Long announcementId, String title, boolean allLots, List<String> lotNumbers) {
}
