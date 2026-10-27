package io.github.rennaraujo.sage.auth;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * API sem estado, autenticada por chave no cabeçalho {@code Authorization}. Não há sessão nem cookie, então não há
 * CSRF a proteger em {@code /api/**} (o navegador não anexa a chave sozinho). Login por formulário, Basic e usuário
 * padrão ficam desligados.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ApiKeyProperties.class, RateLimitProperties.class})
class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;
    private static final String READER = Role.READER.name();
    private static final String EDITOR = Role.EDITOR.name();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyProperties keys) throws Exception {
        if (keys.keys().isEmpty()) {
            LOG.warn("Nenhuma chave em sage.auth.keys: todos os endpoints de /api serão recusados (401)");
        }
        ProblemJsonHandlers problems = new ProblemJsonHandlers();
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/chat/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/perguntas")
                        .hasAnyRole(READER, EDITOR)
                        .requestMatchers("/api/documentos/**", "/api/lacunas/**", "/api/importar/**")
                        .hasRole(EDITOR)
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .anonymous(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Existir um {@link AuthenticationManager} faz o Spring Boot não criar o usuário padrão com senha gerada. Ninguém
     * autentica por usuário e senha aqui, então ele recusa tudo.
     */
    @Bean
    AuthenticationManager noPasswordAuthenticationManager() {
        return authentication -> {
            throw new BadCredentialsException("Autenticação por usuário e senha não existe nesta API");
        };
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties) {
        return new RateLimiter(properties);
    }
}
