---
id: publico/spring-boot/3.5/actuator.md
fonte: publico
linguagem: spring-boot
versao: "3.5"
secao: Actuator
url_origem: https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html
coletado_em: "2026-10-01"
revisado: true
---
# Actuator endpoints

Add spring-boot-starter-actuator to get production-ready endpoints such as health, info, metrics and env under /actuator.

## Exposure

Over HTTP only the health endpoint is exposed by default. To expose more, list them in management.endpoints.web.exposure.include, for example health,info,metrics, or use an asterisk for all of them. The exclude property removes endpoints from that list. Exposing everything is risky because endpoints like env and heapdump reveal internals, so protect them with Spring Security or move them to another port with management.server.port.

## Health

The health endpoint hides the details by default. Set management.endpoint.health.show-details to when-authorized or always to see the status of each component. Health groups combine indicators: management.endpoint.health.group.readiness.include=db,diskSpace creates /actuator/health/readiness.

With management.endpoint.health.probes.enabled=true, the liveness and readiness groups are added for Kubernetes probes. A failing readiness check takes the pod out of the load balancer without restarting it.

## Custom indicators

Implement HealthIndicator, or extend AbstractHealthIndicator, and register it as a bean to add your own check. The bean name without the HealthIndicator suffix becomes the component name.
