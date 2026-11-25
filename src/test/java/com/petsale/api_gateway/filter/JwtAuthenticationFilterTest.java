package com.petsale.api_gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "mySuperSecretKeyForPetShopEcommerceApp12345";

    private JwtAuthenticationFilter filter;
    private GatewayFilter gatewayFilter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "secret", SECRET);
        gatewayFilter = filter.apply(new JwtAuthenticationFilter.Config());
    }

    @Test
    void rejectsRequestsWithoutAnAuthorizationHeader() {
        ServerWebExchange exchange = exchangeWithoutHeader();

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsNonBearerSchemes() {
        ServerWebExchange exchange = exchangeWith("Basic dXNlcjpwYXNzd29yZA==");

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsLowercaseBearerScheme() {
        // Characterization: the scheme check is case-sensitive, although RFC 7235 defines it
        // as case-insensitive. "bearer <valid-token>" is refused.
        ServerWebExchange exchange = exchangeWith("bearer " + validToken());

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnEmptyBearerToken() {
        ServerWebExchange exchange = exchangeWith("Bearer ");

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMalformedTokens() {
        ServerWebExchange exchange = exchangeWith("Bearer not-a-jwt");

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsExpiredTokens() {
        String expired = Jwts.builder()
                .subject("alice")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(signingKey())
                .compact();
        ServerWebExchange exchange = exchangeWith("Bearer " + expired);

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokensSignedWithAnotherSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "aCompletelyDifferentSecretKeyThatIsLongEnough".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();
        ServerWebExchange exchange = exchangeWith("Bearer " + foreignToken);

        gatewayFilter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptsAValidTokenAndContinuesTheChain() {
        ServerWebExchange exchange = exchangeWith("Bearer " + validToken());
        GatewayFilterChain chain = chain();

        gatewayFilter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private String validToken() {
        return Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(signingKey())
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        return chain;
    }

    private ServerWebExchange exchangeWithoutHeader() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/orders").build());
    }

    private ServerWebExchange exchangeWith(String authorizationHeader) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .build());
    }
}
