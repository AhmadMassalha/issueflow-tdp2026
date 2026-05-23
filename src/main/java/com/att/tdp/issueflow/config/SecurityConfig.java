package com.att.tdp.issueflow.config;

import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.auth.jwt.TokenDenyList;
import com.att.tdp.issueflow.auth.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless JWT security configuration.
 *
 * <p>Filter chain:
 * <pre>
 *   ... → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter → ... → controllers
 * </pre>
 *
 * <p>{@code AuthenticationEntryPoint} (401) and {@code AccessDeniedHandler}
 * (403) both delegate to {@link HandlerExceptionResolver} so the
 * {@code @RestControllerAdvice} envelope is used instead of Spring Security's
 * default JSON shape.
 *
 * <p>{@code permitAll()} is limited to the two paths spec 02 §3 enumerates
 * ({@code /auth/login} and {@code /error}). Every other URL — including
 * {@code /users} — requires a valid bearer token.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Explicit bean registration (rather than {@code @Component} on the filter class)
     * keeps {@code @WebMvcTest} slices from auto-discovering the filter and trying
     * to wire a {@code JwtService} that lives outside the web slice. See the
     * companion Gotchas note in {@code .cursor/rules/30-testing.mdc}.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwt,
            TokenDenyList denyList,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new JwtAuthenticationFilter(jwt, denyList, resolver);
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (req, res, ex) -> resolver.resolveException(req, res, null, ex);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (req, res, ex) -> resolver.resolveException(req, res, null, ex);
    }
}
