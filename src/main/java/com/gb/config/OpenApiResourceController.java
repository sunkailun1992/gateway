package com.gb.config;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI resource aggregation for routes loaded from Nacos Gateway config.
 */
@RestController
public class OpenApiResourceController {

    private static final String PATH_PREDICATE = "Path";
    private static final String API_DOCS_PATH = "v3/api-docs";

    private final GatewayProperties gatewayProperties;

    public OpenApiResourceController(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    @GetMapping("/swagger-resources")
    public Mono<ResponseEntity<List<Map<String, String>>>> swaggerResources() {
        List<Map<String, String>> resources = new ArrayList<>();
        gatewayProperties.getRoutes().forEach(route -> route.getPredicates().stream()
                .filter(predicate -> PATH_PREDICATE.equalsIgnoreCase(predicate.getName()))
                .findFirst()
                .map(predicate -> predicate.getArgs().get(NameUtils.GENERATED_NAME_PREFIX + "0"))
                .map(path -> path.replace("**", API_DOCS_PATH))
                .ifPresent(location -> resources.add(swaggerResource(route.getId(), location))));
        return Mono.just(ResponseEntity.ok(resources));
    }

    private Map<String, String> swaggerResource(String name, String location) {
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("name", name);
        resource.put("location", location);
        resource.put("swaggerVersion", "3.0");
        return resource;
    }
}
