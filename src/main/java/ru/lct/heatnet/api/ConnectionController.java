package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.profile.ProfileService;
import java.io.IOException;
import java.util.Map;

/** Read-only explanation of saved routes; no new routing or export. */
@RestController
@Profile("!cli")
@RequestMapping("/api/v1/tracing/jobs")
public class ConnectionController {
    private final ProfileService profiles;
    public ConnectionController(ProfileService profiles) {this.profiles=profiles;}

    @Operation(summary="Карточка подключения выбранного ОКС",
            description="Без oks_key — список всех ОКС. С oks_key — факты сохранённого пути, ввода, общих участков и пересечений. Базовый и глубинный режимы, только чтение.")
    @GetMapping("/{id}/connection")
    public Map<String,Object> connection(@PathVariable String id,
            @RequestParam("variant_id") String variant,
            @RequestParam(value="oks_key",required=false) String oksKey) {
        try {return profiles.getConnection(id,variant,oksKey);}
        catch(IOException e) {throw new ResponseStatusException(HttpStatus.CONFLICT,"Не удалось прочитать данные подключения",e);}
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("code","CONNECTION_UNAVAILABLE",
                "message",exception.getReason()==null?"Карточка подключения недоступна":exception.getReason()));
    }
}
