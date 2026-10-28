---
id: publico/spring-boot/4.1/actuator.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: Actuator
url_origem: https://docs.spring.io/spring-boot/4.1/reference/actuator/endpoints.html
coletado_em: "2026-10-01"
revisado: true
---
# Actuator endpoints

Add spring-boot-starter-actuator to enable production-ready endpoints under /actuator. The actuator code is split into smaller modules in Spring Boot 4, but the starter still brings everything that is needed for the web endpoints.

## Exposure

Only the health endpoint is exposed over HTTP by default. Use management.endpoints.web.exposure.include to expose others, for example health,info,metrics, and management.endpoints.web.exposure.exclude to remove some. Do not expose every endpoint on a public port. Sensitive ones such as env and heapdump should be behind authentication or on a separate management port, set with management.server.port.

## Health

Details are hidden by default. management.endpoint.health.show-details accepts never, when-authorized and always. Health groups, for instance management.endpoint.health.group.readiness.include, create sub-paths such as /actuator/health/readiness. The Kubernetes probes are enabled with management.endpoint.health.probes.enabled=true.

## Custom indicators

A HealthIndicator bean adds a component to the health response. The component name is the bean name without the HealthIndicator suffix.
