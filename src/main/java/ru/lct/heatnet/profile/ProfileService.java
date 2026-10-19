package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lazy, read-only view of a completed job; deliberately separate from calculation. */
@Service
@Profile("!cli")
public class ProfileService {
    private final JobService jobs;
    private final RestrictionRules serverRules;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    // Compact graph/footprints only. No InputModel, input text or complete profiles retained.
    private final LinkedHashMap<String, ProfileScene> cache = new LinkedHashMap<>(4,0.75f,true);
    private static final int MAX_CACHED_COORDINATES = 150_000;
    private int cachedCoordinates;

    public ProfileService(JobService jobs, @Value("${heatnet.rules-file:}") String rulesFile) throws IOException {
        this.jobs=jobs;
        this.serverRules=RestrictionRules.load(rulesFile==null||rulesFile.isBlank()?null:Paths.get(rulesFile));
    }

    public Map<String,Object> get(String id, String variant, String oksKey) throws IOException {
        JobEntity job=jobs.get(id);
        if(job==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Задача не найдена");
        if(job.getStatus()!=JobEntity.Status.DONE || job.getDepthResultPath()==null || job.getInputPath()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Глубинный профиль недоступен для этой задачи");
        Path input=Paths.get(job.getInputPath()), depth=Paths.get(job.getDepthResultPath());
        if(!Files.isRegularFile(input)||!Files.isRegularFile(depth))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Файлы глубинного результата недоступны");
        Path rules=input.resolveSibling(job.getId()+"-rules.yml");
        try {
            ProfileScene scene=load(input,depth,rules,variant);
            return oksKey==null?scene.list():scene.profile(oksKey);
        } catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage(),e);
        }
    }

    private synchronized ProfileScene load(Path input,Path depth,Path rules,String variant) throws IOException {
        String signature=stamp(input)+"|"+stamp(depth)+"|"+(Files.exists(rules)?stamp(rules):"builtin")+"|"+variant;
        ProfileScene existing=cache.get(signature);
        if(existing!=null) return existing;
        RestrictionRules effective=serverRules;
        if(Files.isRegularFile(rules)) try(InputStream in=Files.newInputStream(rules)) { effective=serverRules.mergeYaml(in); }
        InputModel model;
        try(InputStream in=Files.newInputStream(input)) { model=new GeoJsonReader(new Projection()).read(in); }
        ProfileScene scene;
        // Match GeoJsonReader: isNaN probing must not round valid 1e400 numeric IDs through double.
        try(InputStream in=Files.newInputStream(depth);
            JsonParser parser=new JsonParserDelegate(mapper.getFactory().createParser(in)) {
                @Override public boolean isNaN() { return false; }
            }) { scene=new ProfileScene(model,mapper.readTree(parser),effective,variant); }
        int count=scene.coordinateCount();
        if(count<=MAX_CACHED_COORDINATES) {
            Iterator<Map.Entry<String,ProfileScene>> iterator=cache.entrySet().iterator();
            while(iterator.hasNext() && (cache.size()>=2 || cachedCoordinates+count>MAX_CACHED_COORDINATES)) {
                Map.Entry<String,ProfileScene> entry=iterator.next(); cachedCoordinates-=entry.getValue().coordinateCount(); iterator.remove();
            }
            cache.put(signature,scene); cachedCoordinates+=count;
        }
        return scene;
    }

    private static String stamp(Path path) throws IOException {
        return path.toAbsolutePath().normalize()+":"+Files.size(path)+":"+Files.getLastModifiedTime(path);
    }
}
