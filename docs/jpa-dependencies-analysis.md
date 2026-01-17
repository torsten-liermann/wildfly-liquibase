# JPA Subsystem Abhängigkeiten Analyse

## JPA Deployment Phasen

JPA wird in mehreren Phasen deployed:

### 1. PARSE Phase (Phase.PARSE_PERSISTENCE_UNIT)
- **PersistenceUnitParseProcessor**: Liest persistence.xml

### 2. DEPENDENCIES Phase
- **JPAAnnotationProcessor** (DEPENDENCIES_PERSISTENCE_ANNOTATION): Verarbeitet @PersistenceContext, @PersistenceUnit
- **JPADependencyProcessor** (DEPENDENCIES_JPA): Fügt JPA Module hinzu
- **HibernateSearchProcessor** (DEPENDENCIES_HIBERNATE_SEARCH): Optional für Hibernate Search

### 3. FIRST_MODULE_USE Phase (wichtig!)
- **JPAClassFileTransformerProcessor** (256): Registriert ClassFileTransformer
- **JPAInterceptorProcessor** (512): EJB Interceptoren (optional)
- **PersistenceBeginInstallProcessor** (768): **STARTET PU Services Phase 1**

### 4. POST_MODULE Phase
- **PersistenceRefProcessor** (POST_MODULE_PERSISTENCE_REF): Verarbeitet persistence-ref aus deployment descriptors

### 5. INSTALL Phase
- **PersistenceCompleteInstallProcessor** (INSTALL_PERSISTENTUNIT = 4640): **Komplettiert PU Services Phase 2**

## Service-Abhängigkeiten von JPA

Die kritischen Abhängigkeiten werden in `PersistenceUnitServiceHandler.deployPersistenceUnit()` definiert:

### 1. DataSource Abhängigkeiten
```java
// JTA DataSource
builder.addDependency(ContextNames.bindInfoFor(jtaDataSource).getBinderServiceName(), 
                     ManagedReferenceFactory.class, 
                     new ManagedReferenceFactoryInjector(service.getJtaDataSourceInjector()));

// Non-JTA DataSource (falls definiert)
builder.addDependency(ContextNames.bindInfoFor(nonJtaDataSource).getBinderServiceName(), 
                     ManagedReferenceFactory.class, 
                     new ManagedReferenceFactoryInjector(service.getNonJtaDataSourceInjector()));
```

### 2. JPA Service Abhängigkeit
```java
builder.requires(JPAServiceNames.getJPAServiceName());
```

### 3. Phase 1 Service Abhängigkeit (bei Two-Phase Bootstrap)
```java
builder.addDependency(puServiceName.append(FIRST_PHASE), 
                     PhaseOnePersistenceUnitServiceImpl.class, 
                     service.getPhaseOnePersistenceUnitServiceImplInjector());
```

## Two-Phase Bootstrap

JPA kann in zwei Phasen starten:

1. **Phase 1** (FIRST_MODULE_USE_PERSISTENCE_PREPARE = 768):
   - Erstellt EntityManagerFactory Bootstrap
   - Registriert ClassTransformer
   - Noch kein vollständiger EntityManager

2. **Phase 2** (INSTALL_PERSISTENTUNIT = 4640):
   - Komplettiert EntityManagerFactory
   - CDI Integration
   - Vollständiger PU Service

## Warum funktioniert Liquibase trotz später Phase?

Die Erklärung liegt in den **Service-Abhängigkeiten**:

1. **JPA benötigt DataSource**: 
   ```java
   builder.addDependency(ContextNames.bindInfoFor(jtaDataSource).getBinderServiceName(), ...)
   ```

2. **Liquibase blockiert DataSource**:
   - Liquibase registriert sich als Service mit DataSource-Abhängigkeit
   - Die DataSource wird erst als "verfügbar" markiert, wenn Liquibase fertig ist

3. **MSC Service-Reihenfolge**:
   - Obwohl JPA Phase (4640) VOR Liquibase Phase (8248) kommt
   - Wartet JPA auf die DataSource
   - DataSource wartet auf Liquibase
   - Also: Liquibase → DataSource → JPA

## Schlussfolgerung

Die aktuelle Lösung funktioniert durch eine **implizite Abhängigkeitskette**:

```
Liquibase Service 
    ↓ (requires)
DataSource Service
    ↓ (required by)
JPA PersistenceUnit Service
```

Das ist funktional korrekt, aber:
- Nicht offensichtlich aus den Phase-Nummern
- Abhängig von Service-Dependencies statt expliziter Phase-Ordnung
- Schwer zu verstehen ohne tiefe MSC-Kenntnisse

## Empfehlung

Eine explizitere Lösung wäre:
```java
// Nach DataSource-Installation, vor JPA Phase 1
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = 
    Phase.FIRST_MODULE_USE_DSXML_DEPLOYMENT + 0x01;  // 1025
```

Dies würde Liquibase zwischen DataSource (1024) und JPA Phase 1 (768) platzieren - was logisch falsch erscheint, da 768 < 1024. 

Tatsächlich müsste es sein:
```java
// Nach FIRST_MODULE_USE, aber vor JPA Prepare
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = 
    Phase.FIRST_MODULE_USE + 0x100;  // 256
```

Aber das würde VOR DataSource-Installation laufen!