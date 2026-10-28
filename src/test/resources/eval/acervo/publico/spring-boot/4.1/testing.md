---
id: publico/spring-boot/4.1/testing.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: Testing
url_origem: https://docs.spring.io/spring-boot/4.1/reference/testing/spring-boot-applications.html
coletado_em: "2026-10-01"
revisado: true
---
# Testing Spring Boot applications

Use @SpringBootTest to start the application context, with webEnvironment = RANDOM_PORT for a real server. Each technology has its own test starter in Spring Boot 4, such as spring-boot-starter-webmvc-test, in addition to spring-boot-starter-test.

## Mocking beans

@MockBean and @SpyBean were removed in Spring Boot 4.0. Use @MockitoBean and @MockitoSpyBean from Spring Framework instead; they replace a bean in the context during the test.

## Calling the application

TestRestTemplate is in a separate module now. Add the spring-boot-resttestclient dependency and annotate the test with @AutoConfigureTestRestTemplate to inject it. The new RestTestClient is the fluent alternative and can run against a MockMvc setup, a bound controller or a real server.

## Slices and containers

@WebMvcTest and @DataJpaTest still load only part of the context. A Testcontainers bean annotated with @ServiceConnection configures the connection details automatically.
