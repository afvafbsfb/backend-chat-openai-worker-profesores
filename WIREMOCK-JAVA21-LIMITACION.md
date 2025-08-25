# Limitación: WireMock, Jetty y Java 21+

## Contexto
- Proyecto: Spring Boot 3.x, Java 21+.
- Objetivo: Ejecutar tests de integración realistas con WireMock para clientes HTTP.

## Problema
- **WireMock 2.x** requiere Jetty 9 y javax.servlet, pero Java 21+ y Spring Boot 3.x traen dependencias incompatibles (Jetty 11+ o superiores).
- **WireMock 3.x** es compatible con Java 21+ y Jetty 11+, pero actualmente existen conflictos de clases y métodos (por ejemplo, `NoSuchMethodError` y `IncompatibleClassChangeError`) debido a la coexistencia de versiones Jetty incompatibles en el classpath.
- Spring Boot 3.x y otras dependencias modernas pueden traer Jetty 11+ transitivamente, lo que impide que WireMock 2.x funcione correctamente.

## Síntomas
- Errores como `NoSuchMethodError`, `IncompatibleClassChangeError` y `NoClassDefFoundError` relacionados con clases de Jetty (`org.eclipse.jetty.*`).
- Imposibilidad de ejecutar tests de integración con WireMock en el entorno actual.

## Alternativas y recomendaciones
- **WireMock 2.x + Jetty 9**: Solo viable en proyectos Java 8-17 y Spring Boot 2.x.
- **WireMock 3.x + Jetty 11+**: Requiere que ninguna otra dependencia traiga Jetty 9 o versiones incompatibles. Actualmente, el ecosistema Maven/Spring Boot 3.x no garantiza esto sin conflictos.
- **Proyecto mínimo**: Para validar integración, crear un proyecto Maven aparte solo con WireMock 2.x y Jetty 9, usando Java 17.
- **Esperar actualización**: Seguir los issues de WireMock y Jetty para futuras versiones compatibles con Java 21+ y Spring Boot 3.x.

## Decisión
- **Se dejan fuera los tests de integración con WireMock** hasta que el ecosistema resuelva los conflictos de Jetty/WireMock para Java 21+.
- Se priorizan tests unitarios y mocks para avanzar en el desarrollo.

---

*Última actualización: 2025-08-24*
