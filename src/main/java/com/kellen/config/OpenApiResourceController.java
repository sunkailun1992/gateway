package com.kellen.config;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .flatMap(this::splitPathPatterns)
                .map(this::apiDocsLocation)
                .filter(Objects::nonNull)
                .min(Comparator.comparing(location -> routePrefixPriority(route.getId(), location)))
                .ifPresent(location -> resources.add(swaggerResource(route.getId(), location))));
        return Mono.just(ResponseEntity.ok(resources));
    }

    private java.util.stream.Stream<String> splitPathPatterns(String pathExpression) {
        if (pathExpression == null) {
            return java.util.stream.Stream.empty();
        }
        return Arrays.stream(pathExpression.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty());
    }

    private String apiDocsLocation(String pathPattern) {
        int wildcardIndex = pathPattern.indexOf("**");
        if (wildcardIndex < 0) {
            return null;
        }
        return pathPattern.substring(0, wildcardIndex) + API_DOCS_PATH;
    }

    private int routePrefixPriority(String routeId, String location) {
        return location.startsWith("/" + routeId + "/") ? 0 : 1;
    }

    private Map<String, String> swaggerResource(String name, String location) {
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("name", name);
        resource.put("location", location);
        resource.put("swaggerVersion", "3.0");
        return resource;
    }
}
