package com.gondolia.realtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tiempo real")
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceTracker presenceTracker;

    @Operation(summary = "Agentes de soporte conectados",
            description = "Cantidad de agentes de soporte con el chat abierto. Los cambios llegan en vivo por "
                    + "/topic/support/presence.")
    @GetMapping("/support")
    public PresenceTracker.PresenceDto support() {
        return presenceTracker.snapshot();
    }
}
