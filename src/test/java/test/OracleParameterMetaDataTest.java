package test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Reader;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OracleParameterMetaDataTest {

  @Container
  static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-xe:21-faststart");

  @BeforeAll
  static void createDb() throws Exception {
    try (Connection conn = connect(); Reader reader = Resources.getResourceAsReader("test/CreateDB.sql")) {
      ScriptRunner runner = new ScriptRunner(conn);
      runner.setLogWriter(null);
      runner.runScript(reader);
    }
  }

  /**
   * Passes on 3.5.19. Fails on 3.6.0 with a NullPointerException
   * thrown by OracleParameterMetaData.getParameterType().
   */
  @Test
  void shouldSelectWithInListFollowedByUntypedBind() {
    SqlSessionFactory factory = sqlSessionFactory(false);
    try (SqlSession sqlSession = factory.openSession()) {
      List<User> users = sqlSession.getMapper(Mapper.class).getUsers(List.of(1, 2), 10);
      assertEquals(2, users.size());
    }
  }

  /**
   * Same statement with the ignoreParameterMetaData option (3.6.1+).
   */
  @Test
  void shouldSelectWhenParameterMetaDataIsIgnored() {
    SqlSessionFactory factory = sqlSessionFactory(true);
    try (SqlSession sqlSession = factory.openSession()) {
      List<User> users = sqlSession.getMapper(Mapper.class).getUsers(List.of(1, 2), 10);
      assertEquals(2, users.size());
    }
  }

  /**
   * Plain JDBC, no MyBatis: the driver reports an untyped bind as
   * SQLException (ORA-17023), except when it follows an IN list
   * with two or more binds. Then it throws a NullPointerException.
   */
  @Test
  void driverThrowsNpeInsteadOfSqlExceptionAfterInList() throws Exception {
    try (Connection conn = connect()) {
      // Untyped bind without a preceding IN list: SQLException
      try (PreparedStatement ps = conn.prepareStatement("select * from users where id = ? and ROWNUM <= ?")) {
        ParameterMetaData md = ps.getParameterMetaData();
        assertEquals(java.sql.Types.NUMERIC, md.getParameterType(1));
        assertThrows(SQLException.class, () -> md.getParameterType(2));
      }
      // Same bind after an IN list with two binds: NullPointerException
      try (PreparedStatement ps = conn.prepareStatement("select * from users where id in (?, ?) and ROWNUM <= ?")) {
        ParameterMetaData md = ps.getParameterMetaData();
        assertEquals(java.sql.Types.NUMERIC, md.getParameterType(1));
        assertEquals(java.sql.Types.NUMERIC, md.getParameterType(2));
        assertThrows(NullPointerException.class, () -> md.getParameterType(3));
      }
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
  }

  private static SqlSessionFactory sqlSessionFactory(boolean ignoreParameterMetaData) {
    UnpooledDataSource dataSource = new UnpooledDataSource(ORACLE.getDriverClassName(), ORACLE.getJdbcUrl(),
        ORACLE.getUsername(), ORACLE.getPassword());
    Configuration configuration = new Configuration(
        new Environment("test", new JdbcTransactionFactory(), dataSource));
    if (ignoreParameterMetaData) {
      enableIgnoreParameterMetaData(configuration);
    }
    configuration.addMapper(Mapper.class);
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  // Reflection keeps this compiling against 3.5.19 and 3.6.0.
  private static void enableIgnoreParameterMetaData(Configuration configuration) {
    try {
      Method setter = Configuration.class.getMethod("setIgnoreParameterMetaData", boolean.class);
      setter.invoke(configuration, true);
    } catch (NoSuchMethodException e) {
      Assumptions.abort("ignoreParameterMetaData requires MyBatis 3.6.1+");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
