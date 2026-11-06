# Oracle: NPE from `getParameterMetaData()` escapes `getParamJdbcType()`

Repro for the Oracle part of mybatis/mybatis-3#3829.

```sh
mvn test                                  # MyBatis 3.6.0 (fails)
mvn test -Dmybatis.version=3.5.19         # passes
mvn test -Dmybatis.version=3.6.1-SNAPSHOT # fails unless ignoreParameterMetaData=true
```

Needs Docker (or Podman) for Testcontainers. Image: `gvenzl/oracle-xe:21-faststart`.
Driver: `ojdbc11` 23.26.4.0.0 (23.26.3.0.0 behaves the same).

## Condition

The statement has an `IN` list with two or more binds, followed by a bind
whose type Oracle cannot describe (e.g. `ROWNUM <= ?` or `? is null`):

```sql
select id, name from users where id in (?, ?) and ROWNUM <= ?
```

For that bind, `OracleParameterMetaData.getParameterType()` throws

```
java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()"
  because "this.parameterType[this.compressedStatement.compressedBinds[...]]" is null
```

Without the preceding `IN` list (or with a single-bind `IN` list), the driver
reports the same bind as `SQLFeatureNotSupportedException` (ORA-17023
"Could not determine type"), which `getParamJdbcType()` already catches.

| SQL | untyped bind reported as |
|---|---|
| `... where id = ? and ROWNUM <= ?` | ORA-17023 (`SQLException`) |
| `... where ROWNUM <= ? and id in (?, ?)` | ORA-17023 (`SQLException`) |
| `... where id in (?) and ROWNUM <= ?` | ORA-17023 (`SQLException`) |
| `... where id in (?, ?) and ROWNUM <= ?` | `NullPointerException` |
| `... where id in (?, ?) and ? is null` | `NullPointerException` |

## Results

| MyBatis | `shouldSelectWithInListFollowedByUntypedBind` | `shouldSelectWhenParameterMetaDataIsIgnored` |
|---|---|---|
| 3.5.19 | pass | skipped (option does not exist) |
| 3.6.0 | **NPE** | skipped (option does not exist) |
| 3.6.1-SNAPSHOT | **NPE** | pass |

`driverThrowsNpeInsteadOfSqlExceptionAfterInList` uses plain JDBC and
documents the driver behaviour; it passes with every MyBatis version.
