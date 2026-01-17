# Bessere Phase-Alternativen für Liquibase

## Aktuelle Situation
```java
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_MDB_DELIVERY_DEPENDENCIES + 0x01;
```

## Semantisch passendere Alternativen

### Option 1: Nach JNDI-Dependencies (EMPFOHLEN)
```java
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_JNDI_DEPENDENCIES + 0x01;
```
**Vorteile:**
- JNDI-Dependencies sind genau das, was Liquibase braucht (DataSource JNDI lookup)
- Semantisch korrekt: "Nach JNDI-Verfügbarkeit, vor JPA"
- Klare Abhängigkeit ohne MDB-Bezug

### Option 2: Nach Module JNDI Bindings
```java
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_MODULE_JNDI_BINDINGS + 0x01;
```
**Vorteile:**
- Module JNDI Bindings beinhalten DataSource-Bindings
- Noch spezifischer als JNDI_DEPENDENCIES
- Garantiert DataSource-Verfügbarkeit

### Option 3: Nach Default Bindings
```java
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_DEFAULT_BINDINGS_EE_CONCURRENCY + 0x01;
```
**Vorteile:**
- Default Bindings sind abgeschlossen
- Vor Component-Installation (und damit vor JPA)
- Klare Position im EE-Stack

### Option 4: Eigene Phase-Konstante
```java
// Definiere eigene semantisch klare Konstante
private static final int INSTALL_DATABASE_MIGRATION = Phase.INSTALL + 0x0500;
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = INSTALL_DATABASE_MIGRATION;
```
**Vorteile:**
- Selbstdokumentierend
- Unabhängig von anderen Subsystemen
- Flexibel anpassbar

## Empfehlung

**Option 1 (INSTALL_JNDI_DEPENDENCIES + 0x01)** ist die beste Wahl:

1. **Semantisch korrekt**: Liquibase benötigt JNDI für DataSource-Zugriff
2. **Technisch sicher**: Nach JNDI, vor JPA
3. **Verständlich**: Jeder versteht die Abhängigkeit
4. **Zukunftssicher**: JNDI wird immer vor JPA initialisiert

## Implementation
```java
// In LiquibaseSubsystemAdd.java
// Execute after JNDI dependencies are resolved (for DataSource access)
// but before JPA initialization (Phase.INSTALL_PERSISTENTUNIT)
private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_JNDI_DEPENDENCIES + 0x01;
private static final int INSTALL_LIQUIBASE_MIGRATION_EXECUTION = INSTALL_LIQUIBASE_CHANGE_LOG + 0x01;
```

## Warum nicht MDB_DELIVERY_DEPENDENCIES?

MDB (Message Driven Beans) haben nichts mit Datenbank-Migrationen zu tun. Die aktuelle Wahl funktioniert zwar, ist aber:
- Semantisch irreführend
- Schwer zu verstehen
- Nicht selbstdokumentierend

Die vorgeschlagenen Alternativen sind klarer und wartungsfreundlicher.