---
id: publico/spring-boot/3.5/testing.md
fonte: publico
linguagem: spring-boot
versao: "3.5"
secao: Testing
url_origem: https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html
coletado_em: "2026-10-01"
revisado: true
---
# Testing Spring Boot applications

The spring-boot-starter-test dependency brings JUnit 5, AssertJ, Mockito and the Spring test support. Annotate a test with @SpringBootTest to start the full application context. Use webEnvironment = RANDOM_PORT to start a real server on a free port and call it with TestRestTemplate.

## Slices

@WebMvcTest loads only the web layer, so controllers are tested with MockMvc and their collaborators are replaced by mocks. @DataJpaTest loads only the JPA components and uses an embedded database. Slices start faster than a full context because they skip the rest of the beans.

## Mocking beans

@MockBean and @SpyBean are deprecated in 3.5 in favor of @MockitoBean and @MockitoSpyBean, which come from Spring Framework. The old annotations still work and print a deprecation warning at compile time.

## Containers

With Testcontainers, a container bean annotated with @ServiceConnection configures the connection details automatically, so there is no need to copy the JDBC URL into a dynamic property.
