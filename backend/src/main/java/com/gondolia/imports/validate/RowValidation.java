package com.gondolia.imports.validate;

import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportField;
import com.gondolia.imports.parse.ParsedRow;
import java.util.ArrayList;
import java.util.List;

/**
 * Resultado de validar una fila: estado, qué va a hacer al aplicarse, los mensajes por celda y los valores tipados
 * que usa la aplicación.
 */
public record RowValidation(ImportRowStatus status, ImportRowAction action, List<Message> messages, ParsedRow parsed,
                            Long branchId, Long existingProductId, boolean createsCategory, boolean createsSupplier) {

    /** Nivel de un mensaje de validación. */
    public enum Level {
        ERROR, WARNING
    }

    /** Mensaje asociado a una celda ({@code field} null = la fila entera). */
    public record Message(ImportField field, Level level, String text) {

        public ImportDtos.RowMessageDto toDto() {
            return new ImportDtos.RowMessageDto(field == null ? null : field.key(), level.name(), text);
        }
    }

    public RowValidation {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean hasErrors() {
        return messages.stream().anyMatch(m -> m.level() == Level.ERROR);
    }

    public List<ImportDtos.RowMessageDto> messageDtos() {
        List<ImportDtos.RowMessageDto> dtos = new ArrayList<>(messages.size());
        for (Message message : messages) {
            dtos.add(message.toDto());
        }
        return dtos;
    }

    /** Unidades que va a cargar esta fila (0 si no corresponde). */
    public int unitsToLoad(boolean importStock) {
        return importStock && branchId != null ? parsed.quantityOrZero() : 0;
    }
}
