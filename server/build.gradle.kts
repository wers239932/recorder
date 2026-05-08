plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Web
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // Spring Boot Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")
    
    // Spring Boot Data JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    
    // Spring Security (для PasswordEncoder)
    implementation("org.springframework.security:spring-security-crypto")
    
    // База данных (H2 для dev, можно заменить на PostgreSQL)
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    
    // Flyway миграции
    implementation("org.flywaydb:flyway-core")
    
    // WebClient для HTTP-клиента (интеграция с сервисом суммаризации)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    // Spring Boot DevTools
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    
    // Spring Boot Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    
    // Apache Commons IO
    implementation("commons-io:commons-io:2.16.1")
    
    // MapStruct для маппинга DTO <-> Entity (опционально, можно вручную)
    // implementation("org.mapstruct:mapstruct:1.5.5.Final")
    // annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
