# Issue #42: CDI-based vs Subsystem-based Liquibase

## Summary

Issue #42 ("wildfly-liquibase doesn't work with hibernate.hbm2ddl.auto set to validate") is a real issue, but it only affects **CDI-based Liquibase integration**, not the subsystem-based approach.

## Two Different Liquibase Integration Approaches

### 1. Subsystem-based Liquibase (Works with validate)
- Configured via `jboss-all.xml` or subsystem configuration
- Executes during `Phase.INSTALL_MDB_DELIVERY_DEPENDENCIES`
- Runs **before** JPA initialization
- **Works correctly with `hibernate.hbm2ddl.auto=validate`**

### 2. CDI-based Liquibase (Issue #42)
- Uses `liquibase.integration.jakarta.cdi.CDILiquibaseConfig`
- Configured via CDI producers (`@Produces @LiquibaseType`)
- Runs when CDI beans are initialized
- CDI initialization happens **after** JPA
- **Fails with `hibernate.hbm2ddl.auto=validate`**

## The Root Cause

The `LiquibaseHibernateJPAIntegrationTest` demonstrates this issue:

```java
@ApplicationScoped
public class LiquibaseConfigurationProducer {
    @Produces
    @LiquibaseType
    public CDILiquibaseConfig createConfig() {
        // CDI-based configuration
    }
}
```

This CDI-based approach cannot work with schema validation because:
1. JPA starts and validates the schema
2. Schema validation fails (tables don't exist yet)
3. CDI beans are initialized (too late)
4. Liquibase would run now (but deployment already failed)

## The Solution

Use subsystem-based Liquibase configuration for applications that need `hibernate.hbm2ddl.auto=validate`:

### jboss-all.xml
```xml
<jboss umlns="urn:jboss:1.0">
    <liquibase xmlns="urn:jboss:domain:liquibase:1.0">
        <changelog name="changelog.xml"/>
    </liquibase>
</jboss>
```

### Or standalone-full.xml
```xml
<subsystem xmlns="urn:jboss:domain:liquibase:1.0">
    <datasource name="java:jboss/datasources/ExampleDS" 
                change-log-file="changelog.xml"/>
</subsystem>
```

## Test Results

- `LiquibaseJPAOrderingTest`: ✅ Demonstrates subsystem-based Liquibase works with validation
- `LiquibaseHibernateJPAIntegrationTest`: ❌ Shows CDI-based Liquibase fails with validation

## Recommendations

1. **For schema validation**: Use subsystem-based Liquibase
2. **For CDI integration**: Accept that schema must exist or use `hibernate.hbm2ddl.auto=update`
3. **Documentation**: Clearly state this limitation for CDI-based approach
4. **Future enhancement**: Consider a CDI extension that runs earlier in the lifecycle

## Conclusion

Issue #42 is not a bug in the subsystem implementation but a fundamental limitation of CDI lifecycle timing. The subsystem-based approach provides the correct solution for users who need database migrations to run before JPA validation.