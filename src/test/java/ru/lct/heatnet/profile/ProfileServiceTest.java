package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.api.ProfileController;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {
    @TempDir Path dir;

    @Test void endpointReadsJobYamlAndNeverChangesInputOrResults() throws Exception {
        ProfileSceneTest fixture=new ProfileSceneTest();
        fixture.source("source",0);fixture.oks(10L,30);
        fixture.segment("line","source",10L,0,30,3,3);
        JobEntity job=job(fixture);
        Path input=Path.of(job.getInputPath()),depth=Path.of(job.getDepthResultPath()),rules=dir.resolve("sample-rules.yml");
        Files.writeString(rules,"heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        byte[] beforeInput=Files.readAllBytes(input),beforeDepth=Files.readAllBytes(depth),beforeRules=Files.readAllBytes(rules);
        JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        ProfileController endpoint=new ProfileController(new ProfileService(jobs,""));
        assertEquals("d1",endpoint.profile("sample","d1",null).get("variant_id"));
        Map<String,Object> profile=endpoint.profile("sample","d1","n:10");
        assertEquals(30.0,(double)profile.get("total_length"),0.001);
        List<Map<String,Object>> obstacles=fixture.list(profile,"obstacles");
        assertEquals(4.2,obstacles.get(0).get("top_depth"));
        assertEquals("tie_in",obstacles.get(0).get("role"));
        assertArrayEquals(beforeInput,Files.readAllBytes(input));
        assertArrayEquals(beforeDepth,Files.readAllBytes(depth));
        assertArrayEquals(beforeRules,Files.readAllBytes(rules));
        verify(jobs,times(2)).get("sample");verifyNoMoreInteractions(jobs);
    }

    @Test void cacheInvalidatesWhenJobRulesChange() throws Exception {
        ProfileSceneTest fixture=new ProfileSceneTest();
        fixture.source("source",0);fixture.oks(10L,30);
        fixture.segment("line","source",10L,0,30,3,3);
        JobEntity job=job(fixture);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        ProfileService profiles=new ProfileService(jobs,"");
        assertEquals(3.0,fixture.list(profiles.get("sample","d1","n:10"),"obstacles").get(0).get("top_depth"));
        Path rules=dir.resolve("sample-rules.yml");
        Files.writeString(rules,"heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        Files.setLastModifiedTime(rules,FileTime.fromMillis(System.currentTimeMillis()+1000));
        assertEquals(4.2,fixture.list(profiles.get("sample","d1","n:10"),"obstacles").get(0).get("top_depth"));
    }

    @Test void missingIncompleteAndUnavailableVariantsReturnExplicitStatuses() throws Exception {
        JobService jobs=mock(JobService.class);ProfileService profiles=new ProfileService(jobs,"");
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,()->profiles.get("missing","d1",null)).getStatus());
        JobEntity queued=new JobEntity();queued.setStatus(JobEntity.Status.RUNNING);when(jobs.get("pending")).thenReturn(queued);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->profiles.get("pending","d1",null)).getStatus());
        ProfileSceneTest fixture=new ProfileSceneTest();fixture.source("source",0);fixture.oks(10L,30);
        fixture.segment("line","source",10L,0,30,3,3);JobEntity job=job(fixture);when(jobs.get("sample")).thenReturn(job);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->profiles.get("sample","d3",null)).getStatus());
    }

    @Test void preservesFiniteDecimalIdsLargerThanDoubleRange() throws Exception {
        ProfileSceneTest fixture=new ProfileSceneTest();BigDecimal id=new BigDecimal("1e400");
        fixture.source("source",0);fixture.oks(id,30);
        fixture.segment("line","source",id,0,30,3,3);
        JobEntity job=job(fixture);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        Map<String,Object> profile=new ProfileService(jobs,"").get("sample","d1","n:"+id.toPlainString());
        assertEquals("n:"+id.toPlainString(),((Map<?,?>)profile.get("oks")).get("key"));
    }

    @Test void httpErrorsIncludeReadableReason() throws Exception {
        ProfileService profiles=new ProfileService(mock(JobService.class),"");
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new ProfileController(profiles)).build();
        mvc.perform(get("/api/v1/tracing/jobs/missing/profile").param("variant_id","d1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Задача не найдена"));
    }

    private JobEntity job(ProfileSceneTest f) throws Exception {
        ObjectNode fc=f.mapper.createObjectNode().put("type","FeatureCollection");ArrayNode features=fc.putArray("features");
        ObjectNode chamber=features.addObject().put("type","Feature");chamber.putObject("properties").put("id","source").put("object_type","heat_chamber");chamber.set("geometry",f.point(0));
        ObjectNode oks=features.addObject().put("type","Feature");ObjectNode properties=oks.putObject("properties").put("object_type","oks_connection_point").put("flow_tph",1);
        properties.set("id",f.mapper.valueToTree(f.input.oksPoints.get(0).id));oks.set("geometry",f.point(30));
        ObjectNode pipe=features.addObject().put("type","Feature");pipe.putObject("properties").put("id","existing").put("object_type","heat_network").put("diameter",100);
        ArrayNode coords=pipe.putObject("geometry").put("type","LineString").putArray("coordinates");
        for(double y:new double[]{-10,10}) { org.locationtech.jts.geom.Coordinate c=f.at(0,y),w=f.projection.utmToWgs(c.x,c.y);coords.addArray().add(w.x).add(w.y); }
        Path input=dir.resolve("sample-input.geojson"),depth=dir.resolve("sample-result-depth.geojson");
        Files.writeString(input,f.mapper.writeValueAsString(fc));Files.writeString(depth,f.mapper.writeValueAsString(f.collection()));
        JobEntity job=new JobEntity();job.setId("sample");job.setStatus(JobEntity.Status.DONE);job.setInputPath(input.toString());job.setDepthResultPath(depth.toString());return job;
    }
}
