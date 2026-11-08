package com.petsale.api_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

	@org.springframework.context.annotation.Bean
	public org.springframework.web.cors.reactive.CorsWebFilter corsWebFilter() {
		org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
		config.addAllowedOrigin("http://localhost:5173"); // Allow React app
		config.addAllowedMethod("*");
		config.addAllowedHeader("*");

		org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source =
				new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return new org.springframework.web.cors.reactive.CorsWebFilter(source);
	}
}
