package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.profile.ProfileService;
import java.io.IOException;
import java.util.Map;

/** Presentation API; does not mutate jobs or exported competition files. */
@RestController
@Profile("!cli")
@RequestMapping("/api/v1/tracing/jobs")
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles=profiles; }

    @Operation(summary="Продольный профиль выбранного ОКС",
            description="Без oks_key — список ОКС. С oks_key — путь от врезки, опубликованные глубины, уклоны и габариты препятствий. Только чтение.")
    @GetMapping("/{id}/profile")
    public Map<String,Object> profile(@PathVariable String id,
            @RequestParam("variant_id") String variant,
            @RequestParam(value="oks_key",required=false) String oksKey) {
        try { return profiles.get(id,variant,oksKey); }
        catch(IOException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Не удалось прочитать данные профиля",e); }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "code","PROFILE_UNAVAILABLE",
                "message",exception.getReason()==null?"Профиль недоступен":exception.getReason()));
    }
}
