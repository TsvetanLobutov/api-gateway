package com.petsale.api_gateway;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the gateway on a random port and proxies its routes to a stub downstream server, so the
 * real routing table, the JWT filter and the CORS filter are exercised over HTTP. Only the route
 * targets are overridden; predicates and filters stay exactly as configured in application.yaml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingIntegrationTest {

    private static final String SECRET = "mySuperSecretKeyForPetShopEcommerceApp12345";
    private static final Map<String, AtomicInteger> DOWNSTREAM_HITS = new ConcurrentHashMap<>();
    private static final HttpServer DOWNSTREAM;

    static {
        try {
            DOWNSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DOWNSTREAM.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                DOWNSTREAM_HITS.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
                byte[] body = ("{\"downstream\":\"stub\",\"path\":\"" + path + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            DOWNSTREAM.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub downstream server", e);
        }
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @DynamicPropertySource
    static void pointEveryRouteAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.test.stub-port", () -> String.valueOf(DOWNSTREAM.getAddress().getPort()));
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + gatewayPort).build();
        DOWNSTREAM_HITS.clear();
    }

    @Test
    void proxiesOpenRoutesToTheDownstreamService() {
        client.get().uri("/api/products/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.downstream").isEqualTo("stub")
                .jsonPath("$.path").isEqualTo("/api/products/1");
    }

    @Test
    void authRouteNeedsNoToken() {
        client.get().uri("/api/auth/login")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void orderRouteRejectsRequestsWithoutAToken() {
        client.post().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(DOWNSTREAM_HITS).isEmpty();
    }

    @Test
    void orderRouteRejectsInvalidTokens() {
        client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(DOWNSTREAM_HITS).isEmpty();
    }

    @Test
    void orderRouteProxiesRequestsWithAValidToken() {
        client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/orders");
    }

    @Test
    void paymentAndReviewRoutesAreCurrentlyUnauthenticated() {
        // Characterization: only the order route is protected by JwtAuthenticationFilter.
        client.post().uri("/api/payments/checkout")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/reviews/product/1")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownPathsAreNotProxied() {
        client.get().uri("/api/unknown")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(DOWNSTREAM_HITS).isEmpty();
    }

    @Test
    void corsPreflightAllowsLocalhostOrigins() {
        client.options().uri("/api/products/1")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void corsPreflightRejectsForeignOrigins() {
        client.options().uri("/api/products/1")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    private String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
