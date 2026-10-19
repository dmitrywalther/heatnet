package ru.lct.heatnet.profile;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heatnet.job.JobEntity;
import ru.lct.heatnet.job.JobService;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.math.BigDecimal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.api.ConnectionController;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionServiceTest {
    @TempDir Path dir;

    @Test void baseEndpointUsesBaseFileAndDoesNotMutateSavedArtifacts() throws Exception {
        JobEntity job=job(10L);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        Path input=Path.of(job.getInputPath()),base=Path.of(job.getResultPath());
        Path yaml=dir.resolve("sample-rules.yml");Files.writeString(yaml,"heat_network_crossing:\n  vertical:\n    obstacle_top_depth: 4.2\n");
        byte[] beforeInput=Files.readAllBytes(input),beforeBase=Files.readAllBytes(base),beforeYaml=Files.readAllBytes(yaml);
        ProfileService service=new ProfileService(jobs,"");
        Map<String,Object> card=get(service,"sample","v1","n:10");
        assertEquals("connected",card.get("status"));
        assertEquals(java.util.List.of("s:existing"),((Map<?,?>)card.get("source")).get("existing_pipe_keys"));
        assertEquals("v1",get(service,"sample","v1",null).get("variant_id"));
        assertArrayEquals(beforeInput,Files.readAllBytes(input));assertArrayEquals(beforeBase,Files.readAllBytes(base));assertArrayEquals(beforeYaml,Files.readAllBytes(yaml));
        assertThrows(ResponseStatusException.class,()->service.get("sample","d1","n:10"));
        verify(jobs,times(3)).get("sample");verifyNoMoreInteractions(jobs);
    }

    @Test void cacheInvalidatesChangedBaseResultAndKeepsExactHugeTypedIds() throws Exception {
        BigDecimal id=new BigDecimal("1e400");JobEntity job=job(id);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        ProfileService service=new ProfileService(jobs,"");String key="n:"+id.toPlainString();
        Map<String,Object> card=get(service,"sample","v1",key);assertEquals(key,((Map<?,?>)card.get("oks")).get("key"));
        assertEquals(1000.0,((Map<?,?>)card.get("costs")).get("path_segment_cost"));
        Path result=Path.of(job.getResultPath());String changed=Files.readString(result).replace("1000.0","2000.0");Files.writeString(result,changed);
        Files.setLastModifiedTime(result,FileTime.fromMillis(System.currentTimeMillis()+1000));
        assertEquals(2000.0,((Map<?,?>)get(service,"sample","v1",key).get("costs")).get("path_segment_cost"));
    }

    @Test void missingJobsAndUnavailableDepthKeepExplicitStatuses() throws Exception {
        JobEntity job=job(10L);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        ProfileService service=new ProfileService(jobs,"");
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,()->get(service,"missing","v1",null)).getStatus());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->get(service,"sample","d1",null)).getStatus());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->get(service,"sample","v3",null)).getStatus());
    }

    @Test void httpEndpointServesCardsAndReadableErrors() throws Exception {
        JobEntity job=job(10L);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new ConnectionController(new ProfileService(jobs,""))).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/tracing/jobs/sample/connection").param("variant_id","v1").param("oks_key","n:10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("connected")).andExpect(jsonPath("$.segments[0].depth_start").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/tracing/jobs/missing/connection").param("variant_id","v1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONNECTION_UNAVAILABLE")).andExpect(jsonPath("$.message").value("Задача не найдена"));
    }

    @Test void profileAndConnectionShareTheSameTwoSceneCache() throws Exception {
        JobEntity job=job(10L);JobService jobs=mock(JobService.class);when(jobs.get("sample")).thenReturn(job);
        Path base=Path.of(job.getResultPath()),depth=dir.resolve("sample-depth.geojson");
        String json=Files.readString(base);String depthJson=json.replace("\"v1\"","\"d1\"").replace("\"depth_start\":null","\"depth_start\":3").replace("\"depth_end\":null","\"depth_end\":3");
        Files.writeString(depth,depthJson);job.setDepthResultPath(depth.toString());
        ProfileService service=new ProfileService(jobs,"");get(service,"sample","v1","n:10");get(service,"sample","d1","n:10");
        java.lang.reflect.Field field=ProfileService.class.getDeclaredField("cache");field.setAccessible(true);
        assertEquals(2,((Map<?,?>)field.get(service)).size());
        Map<String,Object> before=service.get("sample","d1","n:10");assertEquals(2,((Map<?,?>)field.get(service)).size());
        Files.writeString(base,json.replace("1000.0","3000.0"));Files.setLastModifiedTime(base,FileTime.fromMillis(System.currentTimeMillis()+2000));
        get(service,"sample","v1","n:10");assertEquals(2,((Map<?,?>)field.get(service)).size());
        assertEquals(before,service.get("sample","d1","n:10"));
    }

    @SuppressWarnings("unchecked") private Map<String,Object> get(ProfileService service,String job,String variant,String key) throws Exception {
        java.lang.reflect.Method method=assertDoesNotThrow(()->ProfileService.class.getMethod("getConnection",String.class,String.class,String.class));
        try{return (Map<String,Object>)method.invoke(service,job,variant,key);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new AssertionError(e.getCause());}
    }
    private JobEntity job(Object id) throws Exception {
        ProfileSceneTest f=new ProfileSceneTest();f.source("source",0);f.oks(id,30);f.segment("line","source",id,0,30,3,3);
        ObjectNode props=(ObjectNode)f.features.get(0).get("properties");props.put("variant_id","v1").put("flow_tph",1).put("cost",1000.0).putNull("depth_start").putNull("depth_end");
        ObjectNode input=f.mapper.createObjectNode().put("type","FeatureCollection");ArrayNode features=input.putArray("features");
        ObjectNode chamber=features.addObject().put("type","Feature");chamber.putObject("properties").put("id","source").put("object_type","heat_chamber");chamber.set("geometry",f.point(0));
        ObjectNode oks=features.addObject().put("type","Feature");ObjectNode p=oks.putObject("properties").put("object_type","oks_connection_point").put("flow_tph",1);p.set("id",f.mapper.valueToTree(id));oks.set("geometry",f.point(30));
        ObjectNode pipe=features.addObject().put("type","Feature");pipe.putObject("properties").put("id","existing").put("object_type","heat_network").put("diameter",100);
        ArrayNode coords=pipe.putObject("geometry").put("type","LineString").putArray("coordinates");
        for(double y:new double[]{-10,10}) {org.locationtech.jts.geom.Coordinate c=f.at(0,y),w=f.projection.utmToWgs(c.x,c.y);coords.addArray().add(w.x).add(w.y);}
        Path inputFile=dir.resolve("sample-input.geojson"),result=dir.resolve("sample-result.geojson");Files.writeString(inputFile,f.mapper.writeValueAsString(input));Files.writeString(result,f.mapper.writeValueAsString(f.collection()));
        JobEntity job=new JobEntity();job.setId("sample");job.setStatus(JobEntity.Status.DONE);job.setInputPath(inputFile.toString());job.setResultPath(result.toString());return job;
    }
}
