package com.petsale.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the gateway configuration in {@code application.yaml}: one route per service,
 * each matching its path prefix, and only the order route protected by the JWT filter.
 */
@SpringBootTest
class GatewayRoutesConfigTest {

    private static final Map<String, String> EXPECTED_URI_BY_ROUTE = Map.of(
            "product-service", "http://product-service:8080",
            "order-service", "http://order-service:8082",
            "auth-service", "http://auth-service:8083",
            "payment-service", "http://payment-service:8086",
            "review-service", "http://review-service:8084");

    private static final Map<String, String> EXPECTED_PATH_BY_ROUTE = Map.of(
            "product-service", "/api/products/**",
            "order-service", "/api/orders/**",
            "auth-service", "/api/auth/**",
            "payment-service", "/api/payments/**",
            "review-service", "/api/reviews/**");

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void definesOneRoutePerService() {
        assertThat(routes()).extracting(RouteDefinition::getId)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_URI_BY_ROUTE.keySet());
    }

    @Test
    void routesTargetTheirServiceAndMatchTheirPathPrefix() {
        for (RouteDefinition route : routes()) {
            assertThat(route.getUri().toString()).isEqualTo(EXPECTED_URI_BY_ROUTE.get(route.getId()));
            assertThat(route.getPredicates())
                    .singleElement()
                    .satisfies(predicate -> {
                        assertThat(predicate.getName()).isEqualTo("Path");
                        assertThat(predicate.getArgs().values()).containsExactly(EXPECTED_PATH_BY_ROUTE.get(route.getId()));
                    });
        }
    }

    @Test
    void onlyTheOrderRouteIsProtectedByTheJwtFilter() {
        Map<String, List<String>> filtersByRoute = routes().stream()
                .collect(Collectors.toMap(
                        RouteDefinition::getId,
                        route -> route.getFilters().stream()
                                .map(filter -> filter.getName())
                                .toList(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));

        assertThat(filtersByRoute.get("order-service")).containsExactly("JwtAuthenticationFilter");
        assertThat(filtersByRoute).containsOnlyKeys(
                "product-service", "order-service", "auth-service", "payment-service", "review-service");
        filtersByRoute.forEach((routeId, filters) -> {
            if (!routeId.equals("order-service")) {
                assertThat(filters).as("filters on %s", routeId).isEmpty();
            }
        });
    }

    @Test
    void everyRouteRequiresTheAuthenticationFilterFactoryToExist() {
        List<String> filterNames = routes().stream()
                .flatMap(route -> route.getFilters().stream())
                .map(filter -> filter.getName())
                .toList();

        assertThat(filterNames).containsOnly("JwtAuthenticationFilter");
    }

    private List<RouteDefinition> routes() {
        return routeDefinitionLocator.getRouteDefinitions().collectList().block();
    }
}
