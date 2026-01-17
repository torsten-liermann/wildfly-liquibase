# Datasource Lifecycle und Liquibase Boot-Reihenfolge Analyse

## Aktuelle Situation

Die Liquibase-Subsystem verwendet aktuell:
```java
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_MDB_DELIVERY_DEPENDENCIES + 0x01;
```

Diese Phase-Wahl ist nicht dokumentiert und die Orientierung an MDB (Message Driven Beans) Dependencies ist unklar.

## Datasource Subsystem Boot-Reihenfolge

Aus der Analyse des WildFly Connector/Datasource Subsystems:

### Datasource Deployment Phases
```java
// Phase.PARSE - Parsing von -ds.xml Dateien
addDeploymentProcessor(..., Phase.PARSE, Phase.PARSE_DSXML_DEPLOYMENT, new DsXmlDeploymentParsingProcessor());

// Phase.FIRST_MODULE_USE - Installation der Datasources
addDeploymentProcessor(..., Phase.FIRST_MODULE_USE, Phase.FIRST_MODULE_USE_DSXML_DEPLOYMENT, new DsXmlDeploymentInstallProcessor());

// Phase.INSTALL - JDBC Driver Installation  
addDeploymentProcessor(..., Phase.INSTALL, Phase.INSTALL_JDBC_DRIVER, new DriverProcessor());
```

## Service-Abhängigkeiten

Die Liquibase-Implementation hat bereits die korrekte Service-Abhängigkeit:
```java
// LiquibaseChangeLogExecutionProcessor.java
ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(dataSourceJndiName);
Supplier<DataSourceReferenceFactoryService> dataSourceService = builder.requires(bindInfo.getBinderServiceName());
```

Das bedeutet:
- Liquibase wartet bereits auf die Verfügbarkeit der Datasource
- Die MSC (Modular Service Container) Abhängigkeit garantiert die korrekte Reihenfolge

## Problematik der aktuellen Phase-Wahl

### 1. Unklare Semantik
`INSTALL_MDB_DELIVERY_DEPENDENCIES` bezieht sich auf Message Driven Beans - was hat das mit Datenbank-Migrationen zu tun?

### 2. Zu späte Ausführung?
Die gewählte Phase liegt möglicherweise zu spät im Deployment-Prozess:
- `FIRST_MODULE_USE` - Datasources werden installiert
- `INSTALL` - Verschiedene Services werden installiert
- `INSTALL_MDB_DELIVERY_DEPENDENCIES` - MDB-spezifische Dependencies (sehr spät)

### 3. Bessere Alternative
Eine semantisch sinnvollere Phase wäre direkt nach der Datasource-Installation:
```java
// Option 1: Direkt nach Datasource-Installation
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.FIRST_MODULE_USE_DSXML_DEPLOYMENT + 0x01;

// Option 2: Eigene Phase im frühen INSTALL Bereich
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL + 0x100;
```

## Empfehlung

### Kurzfristig
1. Die aktuelle Implementierung funktioniert durch die Service-Abhängigkeiten korrekt
2. Die MSC-Abhängigkeit auf DataSourceReferenceFactoryService garantiert die richtige Reihenfolge

### Langfristig
1. Die Phase sollte semantisch sinnvoller gewählt werden
2. Ein Kommentar sollte die Wahl begründen:
```java
// Execute after datasources are available but before JPA/Hibernate initialization
// We use INSTALL phase to ensure datasources are ready and migrations run before
// any persistence units are initialized
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL + 0x050;
```

## Fazit

Die aktuelle Implementierung funktioniert korrekt durch:
1. Explizite Service-Abhängigkeit auf Datasource
2. MSC garantiert, dass Liquibase erst startet, wenn die Datasource verfügbar ist
3. Die Phase-Wahl ist zwar semantisch unklar, aber funktional korrekt

Die Orientierung sollte sein: **"Sobald Datasources verfügbar sind, sollten Migrationen laufen"** - nicht an MDB-Dependencies.