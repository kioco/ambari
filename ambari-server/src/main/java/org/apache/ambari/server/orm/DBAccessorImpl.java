/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.orm;

import com.google.inject.Inject;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.helpers.ScriptRunner;
import org.apache.ambari.server.orm.helpers.dbms.DbmsHelper;
import org.apache.ambari.server.orm.helpers.dbms.DerbyHelper;
import org.apache.ambari.server.orm.helpers.dbms.GenericDbmsHelper;
import org.apache.ambari.server.orm.helpers.dbms.MySqlHelper;
import org.apache.ambari.server.orm.helpers.dbms.OracleHelper;
import org.apache.ambari.server.orm.helpers.dbms.PostgresHelper;
import org.apache.ambari.server.utils.CustomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.persistence.internal.helper.DBPlatformHelper;
import org.eclipse.persistence.internal.sessions.DatabaseSessionImpl;
import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.SessionLogEntry;
import org.eclipse.persistence.platform.database.DatabasePlatform;
import org.eclipse.persistence.platform.database.DerbyPlatform;
import org.eclipse.persistence.platform.database.MySQLPlatform;
import org.eclipse.persistence.platform.database.OraclePlatform;
import org.eclipse.persistence.platform.database.PostgreSQLPlatform;
import org.eclipse.persistence.sessions.DatabaseLogin;
import org.eclipse.persistence.sessions.DatabaseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class DBAccessorImpl implements DBAccessor {
  private static final Logger LOG = LoggerFactory.getLogger(DBAccessorImpl.class);
  private final DatabasePlatform databasePlatform;
  private final Connection connection;
  private final DbmsHelper dbmsHelper;
  private Configuration configuration;
  private DatabaseMetaData databaseMetaData;
  private static final String dbURLPatternString = "jdbc:(.*?):.*";
  private Pattern dbURLPattern = Pattern.compile(dbURLPatternString, Pattern.CASE_INSENSITIVE);
  private DbType dbType;
  
  @Inject
  public DBAccessorImpl(Configuration configuration) {
    this.configuration = configuration;

    try {
      Class.forName(configuration.getDatabaseDriver());

      connection = DriverManager.getConnection(configuration.getDatabaseUrl(),
              configuration.getDatabaseUser(),
              configuration.getDatabasePassword());

      connection.setAutoCommit(true); //enable autocommit

      //TODO create own mapping and platform classes for supported databases
      String vendorName = connection.getMetaData().getDatabaseProductName()
              + connection.getMetaData().getDatabaseMajorVersion();
      String dbPlatform = DBPlatformHelper.getDBPlatform(vendorName, new AbstractSessionLog() {
        @Override
        public void log(SessionLogEntry sessionLogEntry) {
          LOG.debug(sessionLogEntry.getMessage());
        }
      });
      this.databasePlatform = (DatabasePlatform) Class.forName(dbPlatform).newInstance();
      this.dbmsHelper = loadHelper(databasePlatform);
    } catch (Exception e) {
      String message = "Error while creating database accessor ";
      LOG.error(message, e);
      throw new RuntimeException(e);
    }
  }

  protected DbmsHelper loadHelper(DatabasePlatform databasePlatform) {
    if (databasePlatform instanceof OraclePlatform) {
      dbType = DbType.ORACLE;
      return new OracleHelper(databasePlatform);
    } else if (databasePlatform instanceof MySQLPlatform) {
      dbType = DbType.MYSQL;
      return new MySqlHelper(databasePlatform);
    } else if (databasePlatform instanceof PostgreSQLPlatform) {
      dbType = DbType.POSTGRES;
      return new PostgresHelper(databasePlatform);
    } else if (databasePlatform instanceof DerbyPlatform) {
      dbType = DbType.DERBY;
      return new DerbyHelper(databasePlatform);
    } else {
      dbType = DbType.UNKNOWN;
      return new GenericDbmsHelper(databasePlatform);
    }
  }

  protected Connection getConnection() {
    return connection;
  }

  @Override
  public Connection getNewConnection() {
    try {
      return DriverManager.getConnection(configuration.getDatabaseUrl(),
              configuration.getDatabaseUser(),
              configuration.getDatabasePassword());
    } catch (SQLException e) {
      throw new RuntimeException("Unable to connect to database", e);
    }
  }

  @Override
  public String quoteObjectName(String name) {
    return dbmsHelper.quoteObjectName(name);
  }

  @Override
  public void createTable(String tableName, List<DBColumnInfo> columnInfo,
          String... primaryKeyColumns) throws SQLException {
    if (!tableExists(tableName)) {
      String query = dbmsHelper.getCreateTableStatement(tableName, columnInfo, Arrays.asList(primaryKeyColumns));

      executeQuery(query);
    }
  }

  protected DatabaseMetaData getDatabaseMetaData() throws SQLException {
    if (databaseMetaData == null) {
      databaseMetaData = connection.getMetaData();
    }

    return databaseMetaData;
  }

  private String convertObjectName(String objectName) throws SQLException {
    //tolerate null names for proper usage in filters
    if (objectName == null) {
      return null;
    }
    DatabaseMetaData metaData = getDatabaseMetaData();
    if (metaData.storesLowerCaseIdentifiers()) {
      return objectName.toLowerCase();
    } else if (metaData.storesUpperCaseIdentifiers()) {
      return objectName.toUpperCase();
    }

    return objectName;
  }

  @Override
  public boolean tableExists(String tableName) throws SQLException {
    boolean result = false;
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet res = metaData.getTables(null, null, convertObjectName(tableName), new String[]{"TABLE"});

    if (res != null) {
      try {
        if (res.next()) {
          return res.getString("TABLE_NAME") != null && res.getString("TABLE_NAME").equalsIgnoreCase(tableName);
        }
      } finally {
        res.close();
      }
    }

    return result;
  }

  public DbType getDbType() {
    return dbType;
  }

  @Override
  public boolean tableHasData(String tableName) throws SQLException {
    String query = "SELECT count(*) from " + tableName;
    Statement statement = getConnection().createStatement();
    boolean retVal = false;
    ResultSet rs = null;
    try {
      rs = statement.executeQuery(query);
      if (rs != null) {
        if (rs.next()) {
          return rs.getInt(1) > 0;
        }
      }
    } catch (Exception e) {
      LOG.error("Unable to check if table " + tableName + " has any data. Exception: " + e.getMessage());
    } finally {
      if (statement != null) {
        statement.close();
      }
      if (rs != null) {
        rs.close();
      }
    }
    return retVal;
  }

  @Override
  public boolean tableHasColumn(String tableName, String columnName) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet rs = metaData.getColumns(null, null, convertObjectName(tableName), convertObjectName(columnName));

    if (rs != null) {
      try {
        if (rs.next()) {
          return rs.getString("COLUMN_NAME") != null && rs.getString("COLUMN_NAME").equalsIgnoreCase(columnName);
        }
      } finally {
        rs.close();
      }
    }

    return false;
  }

  @Override
  public boolean tableHasColumn(String tableName, String... columnName) throws SQLException {
    List<String> columnsList = new ArrayList<String>(Arrays.asList(columnName));
    DatabaseMetaData metaData = getDatabaseMetaData();

    CustomStringUtils.toUpperCase(columnsList);
    ResultSet rs = metaData.getColumns(null, null, convertObjectName(tableName), null);

    if (rs != null) {
      try {
        while (rs.next()) {
          if (rs.getString("COLUMN_NAME") != null) {
            columnsList.remove(rs.getString("COLUMN_NAME").toUpperCase());
          }
        }
      } finally {
        rs.close();
      }
    }

    return columnsList.size() == 0;
  }

  @Override
  public boolean tableHasForeignKey(String tableName, String fkName) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet rs = metaData.getImportedKeys(null, null, convertObjectName(tableName));

    if (rs != null) {
      try {
        while (rs.next()) {
          if (StringUtils.equalsIgnoreCase(fkName, rs.getString("FK_NAME"))) {
            return true;
          }
        }
      } finally {
        rs.close();
      }
    }

    LOG.warn("FK {} not found for table {}", convertObjectName(fkName), convertObjectName(tableName));

    return false;
  }

  @Override
  public boolean tableHasForeignKey(String tableName, String refTableName,
          String columnName, String refColumnName) throws SQLException {
    return tableHasForeignKey(tableName, refTableName, new String[]{columnName}, new String[]{refColumnName});
  }

  @Override
  public boolean tableHasForeignKey(String tableName, String referenceTableName, String[] keyColumns,
          String[] referenceColumns) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    //NB: reference table contains pk columns while key table contains fk columns
    ResultSet rs = metaData.getCrossReference(null, null, convertObjectName(referenceTableName),
            null, null, convertObjectName(tableName));

    List<String> pkColumns = new ArrayList<String>(referenceColumns.length);
    for (String referenceColumn : referenceColumns) {
      pkColumns.add(convertObjectName(referenceColumn));
    }
    List<String> fkColumns = new ArrayList<String>(keyColumns.length);
    for (String keyColumn : keyColumns) {
      fkColumns.add(convertObjectName(keyColumn));
    }

    if (rs != null) {
      try {
        while (rs.next()) {

          String pkColumn = rs.getString("PKCOLUMN_NAME");
          String fkColumn = rs.getString("FKCOLUMN_NAME");

          int pkIndex = pkColumns.indexOf(pkColumn);
          int fkIndex = fkColumns.indexOf(fkColumn);
          if (pkIndex != -1 && fkIndex != -1) {
            if (pkIndex != fkIndex) {
              LOG.warn("Columns for FK constraint should be provided in exact order");
            } else {
              pkColumns.remove(pkIndex);
              fkColumns.remove(fkIndex);
            }

          } else {
            LOG.debug("pkCol={}, fkCol={} not found in provided column names, skipping", pkColumn, fkColumn); //TODO debug
          }

        }
        if (pkColumns.isEmpty() && fkColumns.isEmpty()) {
          return true;
        }

      } finally {
        rs.close();
      }
    }

    return false;

  }

  @Override
  public void createIndex(String indexName, String tableName,
          String... columnNames) throws SQLException {
    String query = dbmsHelper.getCreateIndexStatement(indexName, tableName, columnNames);

    executeQuery(query);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String keyColumn, String referenceTableName,
          String referenceColumn, boolean ignoreFailure) throws SQLException {

    addFKConstraint(tableName, constraintName, new String[]{keyColumn}, referenceTableName,
            new String[]{referenceColumn}, false, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String keyColumn, String referenceTableName,
          String referenceColumn, boolean shouldCascadeOnDelete,
          boolean ignoreFailure) throws SQLException {

    addFKConstraint(tableName, constraintName, new String[]{keyColumn}, referenceTableName,
            new String[]{referenceColumn}, shouldCascadeOnDelete, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String[] keyColumns, String referenceTableName,
          String[] referenceColumns,
          boolean ignoreFailure) throws SQLException {
    addFKConstraint(tableName, constraintName, keyColumns, referenceTableName, referenceColumns, false, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String[] keyColumns, String referenceTableName,
          String[] referenceColumns, boolean shouldCascadeOnDelete,
          boolean ignoreFailure) throws SQLException {
    if (!tableHasForeignKey(tableName, referenceTableName, keyColumns, referenceColumns)) {
      String query = dbmsHelper.getAddForeignKeyStatement(tableName, constraintName,
              Arrays.asList(keyColumns),
              referenceTableName,
              Arrays.asList(referenceColumns),
              shouldCascadeOnDelete);

      try {
        executeQuery(query, ignoreFailure);
      } catch (SQLException e) {
        LOG.warn("Add FK constraint failed"
                + ", constraintName = " + constraintName
                + ", tableName = " + tableName, e.getMessage());
        if (!ignoreFailure) {
          throw e;
        }
      }
    } else {
      LOG.info("Foreign Key constraint {} already exists, skipping", constraintName);
    }
  }

  public boolean tableHasConstraint(String tableName, String constraintName) throws SQLException {
    // this kind of request is well lower level as we querying system tables, due that we need for some the name of catalog.
    String query = dbmsHelper.getTableConstraintsStatement(connection.getCatalog(), tableName);
    ResultSet rs = executeSelect(query);
    if (rs != null) {
      while (rs.next()) {
        if (rs.getString("CONSTRAINT_NAME").equalsIgnoreCase(constraintName)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void addUniqueConstraint(String tableName, String constraintName, String... columnNames)
          throws SQLException {
    if (!tableHasConstraint(tableName, constraintName)) {
      String query = dbmsHelper.getAddUniqueConstraintStatement(tableName, constraintName, columnNames);
      try {
        executeQuery(query);
      } catch (SQLException e) {
        LOG.warn("Add unique constraint failed, constraintName={},tableName={}", constraintName, tableName);
        throw e;
      }
    } else {
      LOG.info("Unique constraint {} already exists, skipping", constraintName);
    }
  }

  @Override
  public void addPKConstraint(String tableName, String constraintName, boolean ignoreErrors, String... columnName) throws SQLException {
    if (!tableHasPrimaryKey(tableName, null) && tableHasColumn(tableName, columnName)) {
      String query = dbmsHelper.getAddPrimaryKeyConstraintStatement(tableName, constraintName, columnName);

      executeQuery(query, ignoreErrors);
    } else {
      LOG.warn("Primary constraint {} not altered to table {} as column {} not present or constraint already exists",
              constraintName, tableName, columnName);
    }
  }

  @Override
  public void addPKConstraint(String tableName, String constraintName, String... columnName) throws SQLException {
    addPKConstraint(tableName, constraintName, false, columnName);
  }

  @Override
  public void renameColumn(String tableName, String oldColumnName,
          DBColumnInfo columnInfo) throws SQLException {
    //it is mandatory to specify type in column change clause for mysql
    String renameColumnStatement = dbmsHelper.getRenameColumnStatement(tableName, oldColumnName, columnInfo);
    executeQuery(renameColumnStatement);

  }

  @Override
  public void addColumn(String tableName, DBColumnInfo columnInfo) throws SQLException {
    if (!tableHasColumn(tableName, columnInfo.getName())) {
      //TODO workaround for default values, possibly we will have full support later
      if (columnInfo.getDefaultValue() != null) {
        columnInfo.setNullable(true);
      }
      String query = dbmsHelper.getAddColumnStatement(tableName, columnInfo);
      executeQuery(query);

      if (columnInfo.getDefaultValue() != null) {
        updateTable(tableName, columnInfo.getName(), columnInfo.getDefaultValue(), "");
      }
    }
  }

  @Override
  public void alterColumn(String tableName, DBColumnInfo columnInfo)
          throws SQLException {
    //varchar extension only (derby limitation, but not too much for others),
    if (dbmsHelper.supportsColumnTypeChange()) {
      String statement = dbmsHelper.getAlterColumnStatement(tableName,
              columnInfo);
      executeQuery(statement);
    } else {
      //use addColumn: add_tmp-update-drop-rename for Derby
      DBColumnInfo columnInfoTmp = new DBColumnInfo(
              columnInfo.getName() + "_TMP",
              columnInfo.getType(),
              columnInfo.getLength());
      String statement = dbmsHelper.getAddColumnStatement(tableName, columnInfoTmp);
      executeQuery(statement);
      updateTable(tableName, columnInfo, columnInfoTmp);
      dropColumn(tableName, columnInfo.getName());
      renameColumn(tableName, columnInfoTmp.getName(), columnInfo);
    }
  }

  @Override
  public void updateTable(String tableName, DBColumnInfo columnNameFrom,
          DBColumnInfo columnNameTo) throws SQLException {
    LOG.info("Executing query: UPDATE TABLE " + tableName + " SET "
            + columnNameTo.getName() + "=" + columnNameFrom.getName());

    String statement = "SELECT * FROM " + tableName;
    int typeFrom = getColumnType(tableName, columnNameFrom.getName());
    int typeTo = getColumnType(tableName, columnNameTo.getName());
    Statement dbStatement = null;
    ResultSet rs = null;
    try {
    dbStatement = getConnection().createStatement(ResultSet.TYPE_SCROLL_SENSITIVE,
            ResultSet.CONCUR_UPDATABLE); 
    rs = dbStatement.executeQuery(statement);

    while (rs.next()) {
      convertUpdateData(rs, columnNameFrom, typeFrom, columnNameTo, typeTo);
      rs.updateRow();
    }
    } finally {
      if (rs != null) {
        rs.close();
      }
      if (dbStatement != null) {
        dbStatement.close();
      }
    }
  }

  private void convertUpdateData(ResultSet rs, DBColumnInfo columnNameFrom,
          int typeFrom,
          DBColumnInfo columnNameTo, int typeTo) throws SQLException {
    if (typeFrom == Types.BLOB && typeTo == Types.CLOB) {
      //BLOB-->CLOB
      Blob data = rs.getBlob(columnNameFrom.getName());
      if (data != null) {
        rs.updateClob(columnNameTo.getName(),
                new BufferedReader(new InputStreamReader(data.getBinaryStream(), Charset.defaultCharset())));
      }
    } else {
      Object data = rs.getObject(columnNameFrom.getName());
      rs.updateObject(columnNameTo.getName(), data);
    }

  }

  @Override
  public boolean insertRow(String tableName, String[] columnNames, String[] values, boolean ignoreFailure) throws SQLException {
    StringBuilder builder = new StringBuilder();
    builder.append("INSERT INTO ").append(tableName).append("(");
    if (columnNames.length != values.length) {
      throw new IllegalArgumentException("number of columns should be equal to number of values");
    }

    for (int i = 0; i < columnNames.length; i++) {
      builder.append(columnNames[i]);
      if (i != columnNames.length - 1) {
        builder.append(",");
      }
    }

    builder.append(") VALUES(");

    for (int i = 0; i < values.length; i++) {
      builder.append(values[i]);
      if (i != values.length - 1) {
        builder.append(",");
      }
    }

    builder.append(")");

    Statement statement = getConnection().createStatement();
    int rowsUpdated = 0;
    String query = builder.toString();
    try {
      rowsUpdated = statement.executeUpdate(query);
    } catch (SQLException e) {
      LOG.warn("Unable to execute query: " + query, e);
      if (!ignoreFailure) {
        throw e;
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }

    return rowsUpdated != 0;
  }

  @Override
  public int updateTable(String tableName, String columnName, Object value,
          String whereClause) throws SQLException {

    StringBuilder query = new StringBuilder(String.format("UPDATE %s SET %s = ", tableName, columnName));

    // Only String and number supported.
    // Taken from: org.eclipse.persistence.internal.databaseaccess.appendParameterInternal
    Object dbValue = databasePlatform.convertToDatabaseType(value);
    String valueString = value.toString();
    if (dbValue instanceof String) {
      valueString = "'" + value.toString() + "'";
    }

    query.append(valueString);
    query.append(" ");
    query.append(whereClause);

    Statement statement = getConnection().createStatement();
    int res = -1;
    try {
      res = statement.executeUpdate(query.toString());
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
    return res;
  }

  @Override
  public int executeUpdate(String query) throws SQLException {
    return executeUpdate(query, false);
  }

  @Override
  public int executeUpdate(String query, boolean ignoreErrors) throws SQLException {
    Statement statement = getConnection().createStatement();
    try {
      return statement.executeUpdate(query);
    } catch (SQLException e) {
      LOG.warn("Error executing query: " + query + ", "
              + "errorCode = " + e.getErrorCode() + ", message = " + e.getMessage());
      if (!ignoreErrors) {
        throw e;
      }
    }
    return 0;  // If error appears and ignoreError is set, return 0 (no changes was made)
  }

  @Override
  public void executeQuery(String query, String tableName, String hasColumnName) throws SQLException {
    if (tableHasColumn(tableName, hasColumnName)) {
      executeQuery(query);
    }
  }

  @Override
  public void executeQuery(String query) throws SQLException {
    executeQuery(query, false);
  }

  @Override
  public ResultSet executeSelect(String query) throws SQLException {
    Statement statement = getConnection().createStatement();
    return statement.executeQuery(query);
  }

  @Override
  public ResultSet executeSelect(String query, int resultSetType, int resultSetConcur) throws SQLException {
    Statement statement = getConnection().createStatement(resultSetType, resultSetConcur);
    return statement.executeQuery(query);
  }  
  
  @Override
  public void executeQuery(String query, boolean ignoreFailure) throws SQLException {
    LOG.info("Executing query: {}", query);
    Statement statement = getConnection().createStatement();
    try {
      statement.execute(query);
    } catch (SQLException e) {
      if (!ignoreFailure) {
        LOG.error("Error executing query: " + query, e);
        throw e;
      } else {
        LOG.warn("Error executing query: " + query + ", "
                + "errorCode = " + e.getErrorCode() + ", message = " + e.getMessage());
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
  }

  @Override
  public void dropTable(String tableName) throws SQLException {
    String query = dbmsHelper.getDropTableStatement(tableName);
    executeQuery(query);
  }


  @Override
  public void truncateTable(String tableName) throws SQLException {
    String query = "DELETE FROM " + tableName;
    executeQuery(query);
  }

  @Override
  public void dropColumn(String tableName, String columnName) throws SQLException {
    if (tableHasColumn(tableName, columnName)) {
      String query = dbmsHelper.getDropTableColumnStatement(tableName, columnName);
      executeQuery(query);
    }
  }

  @Override
  public void dropSequence(String sequenceName) throws SQLException {
    executeQuery(dbmsHelper.getDropSequenceStatement(sequenceName), true);
  }

  @Override
  public void dropFKConstraint(String tableName, String constraintName) throws SQLException {
    dropFKConstraint(tableName, constraintName, false);
  }

  @Override
  public void dropFKConstraint(String tableName, String constraintName, boolean ignoreFailure) throws SQLException {
    // ToDo: figure out if name of index and constraint differs
    if (tableHasForeignKey(convertObjectName(tableName), constraintName)) {
      String query = dbmsHelper.getDropFKConstraintStatement(tableName, constraintName);
      executeQuery(query, ignoreFailure);
    } else {
      LOG.warn("Constraint {} from {} table not found, nothing to drop", constraintName, tableName);
    }
  }

  @Override
  public void dropUniqueConstraint(String tableName, String constraintName, boolean ignoreFailure) throws SQLException {
    if (tableHasConstraint(convertObjectName(tableName), convertObjectName(constraintName))) {
      String query = dbmsHelper.getDropUniqueConstraintStatement(tableName, constraintName);
      executeQuery(query, ignoreFailure);
    } else {
      LOG.warn("Unique constraint {} from {} table not found, nothing to drop", constraintName, tableName);
    }
  }

  @Override
  public void dropUniqueConstraint(String tableName, String constraintName) throws SQLException {
    dropUniqueConstraint(tableName, constraintName, false);
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName, String columnName) throws SQLException {
    if (tableHasPrimaryKey(tableName, columnName)) {
      String query = dbmsHelper.getDropPrimaryKeyStatement(convertObjectName(tableName), constraintName);
      executeQuery(query, false);
    } else {
      LOG.warn("Primary key doesn't exists for {} table, skipping", tableName);
    }
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName, boolean ignoreFailure) throws SQLException {
    /*
     * Note, this is un-safe implementation as constraint name checking will work only for PostgresSQL,
     * MySQL and Oracle doesn't use constraint name for drop primary key
     * Consider to use implementation with column name checking for existed constraint.
     */
    if (tableHasPrimaryKey(tableName, null)) {
      String query = dbmsHelper.getDropPrimaryKeyStatement(convertObjectName(tableName), constraintName);
      executeQuery(query, ignoreFailure);
    } else {
      LOG.warn("Primary key doesn't exists for {} table, skipping", tableName);
    }
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName) throws SQLException {
    dropPKConstraint(tableName, constraintName, false);
  }

  @Override
  /**
   * Execute script with autocommit and error tolerance, like psql and sqlplus
   * do by default
   */
  public void executeScript(String filePath) throws SQLException, IOException {
    BufferedReader br = new BufferedReader(new FileReader(filePath));
    try {
      ScriptRunner scriptRunner = new ScriptRunner(getConnection(), false, false);
      scriptRunner.runScript(br);
    } finally {
      if (br != null) {
        br.close();
      }
    }
  }

  @Override
  public DatabaseSession getNewDatabaseSession() {
    DatabaseLogin login = new DatabaseLogin();
    login.setUserName(configuration.getDatabaseUser());
    login.setPassword(configuration.getDatabasePassword());
    login.setDatasourcePlatform(databasePlatform);
    login.setDatabaseURL(configuration.getDatabaseUrl());
    login.setDriverClassName(configuration.getDatabaseDriver());

    return new DatabaseSessionImpl(login);
  }

  @Override
  public boolean tableHasPrimaryKey(String tableName, String columnName) throws SQLException {
    ResultSet rs = getDatabaseMetaData().getPrimaryKeys(null, null, convertObjectName(tableName));
    boolean res = false;
    try {
      if (rs != null && columnName != null) {
        while (rs.next()) {
          if (rs.getString("COLUMN_NAME").equalsIgnoreCase(columnName)) {
            res = true;
            break;
          }
        }
      } else if (rs != null) {
        res = rs.next();
      }
    } finally {
      if (rs != null) {
        rs.close();
      }
    }
    return res;
  }

  @Override
  public int getColumnType(String tableName, String columnName)
          throws SQLException {
    // We doesn't require any actual result except metadata, so WHERE clause shouldn't match
    int res;
    String query;
    Statement statement = null;
    ResultSet rs = null;
    ResultSetMetaData rsmd = null;
    try {
    query = String.format("SELECT %s FROM %s WHERE 1=2", columnName, convertObjectName(tableName));
    statement = getConnection().createStatement();
    rs = statement.executeQuery(query);
    rsmd = rs.getMetaData();
    res = rsmd.getColumnType(1);
    } finally {
      if (rs != null){
        rs.close();
      }
      if (statement != null) {
        statement.close();
      }
    }
    return res;
  }

  private ResultSetMetaData getColumnMetadata(String tableName, String columnName) throws SQLException {
    // We doesn't require any actual result except metadata, so WHERE clause shouldn't match
    String query = String.format("SELECT %s FROM %s WHERE 1=2", convertObjectName(columnName), convertObjectName(tableName));
    ResultSet rs = executeSelect(query);
    return rs.getMetaData();
  }

  @Override
  public Class getColumnClass(String tableName, String columnName)
          throws SQLException, ClassNotFoundException {
    ResultSetMetaData rsmd = getColumnMetadata(tableName, columnName);
    return Class.forName(rsmd.getColumnClassName(1));
  }

  @Override
  public boolean isColumnNullable(String tableName, String columnName) throws SQLException {
    ResultSetMetaData rsmd = getColumnMetadata(tableName, columnName);
    return !(rsmd.isNullable(1) == ResultSetMetaData.columnNoNulls);
  }

  @Override
  public void setColumnNullable(String tableName, DBAccessor.DBColumnInfo columnInfo, boolean nullable)
          throws SQLException {

    String statement = dbmsHelper.getSetNullableStatement(tableName, columnInfo, nullable);
    executeQuery(statement);
  }

  @Override
  public void setColumnNullable(String tableName, String columnName, boolean nullable)
          throws SQLException {
    try {
      // if column is already in nullable state, we shouldn't do anything. This is important for Oracle
      if (isColumnNullable(tableName, columnName) != nullable) {
        Class columnClass = getColumnClass(tableName, columnName);
        String query = dbmsHelper.getSetNullableStatement(tableName, new DBColumnInfo(columnName, columnClass), nullable);
        executeQuery(query);
      } else {
        LOG.info("Column nullability property is not changed due to {} column from {} table is already in {} state, skipping",
                columnName, tableName, (nullable) ? "nullable" : "not nullable");
      }
    } catch (ClassNotFoundException e) {
      LOG.error("Could not modify table=[], column={}, error={}", tableName, columnName, e.getMessage());
    }
  }

  @Override
  public void changeColumnType(String tableName, String columnName, Class fromType, Class toType) throws SQLException {
    // ToDo: create column with more random name
    String tempColumnName = columnName + "_temp";

    switch (configuration.getDatabaseType()) {
      case ORACLE:
        if (String.class.equals(fromType)
                && (toType.equals(Character[].class))
                || toType.equals(char[].class)) {
          addColumn(tableName, new DBColumnInfo(tempColumnName, toType));
          executeUpdate(String.format("UPDATE %s SET %s = %s", convertObjectName(tableName),
                  convertObjectName(tempColumnName), convertObjectName(columnName)));
          dropColumn(tableName, columnName);
          renameColumn(tableName, tempColumnName, new DBColumnInfo(columnName, toType));
          return;
        }
        break;
    }

    alterColumn(tableName, new DBColumnInfo(columnName, toType, null));
  }

}
