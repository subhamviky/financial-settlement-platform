# settlement-service

> **E2A Java Reference Spike** — Spring Boot Microservice

Built on the [E2A Architecture Framework](https://github.com/subhamviky/e2a-framework)

---

## Stack

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-6DB33F?logo=spring)

---

## Getting Started

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run

# Test
mvn test
```

## Project Structure

```
settlement-service/
├── src/main/java/com/subhamviky/settlement/
│   ├── config/      # Spring configuration
│   ├── domain/      # Domain entities (E2A: CDS View Entity / JPA @Entity)
│   ├── service/     # Business logic  (E2A: BIMP / @Service)
│   ├── controller/  # REST controllers (E2A: OData Binding / @RestController)
│   └── repository/  # Data access
├── src/main/resources/
│   └── application.yml
├── pom.xml
└── Dockerfile
```

## License

Apache License 2.0 — see [LICENSE](LICENSE)
