/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.jdbc.impl;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.hydromatic.avatica.AvaticaPrepareResult;
import net.hydromatic.avatica.AvaticaResultSet;
import net.hydromatic.avatica.AvaticaStatement;

import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.client.DrillClient;
import org.apache.drill.exec.proto.UserBitShared.QueryId;
import org.apache.drill.exec.proto.UserBitShared.QueryResult.QueryState;
import org.apache.drill.exec.proto.UserBitShared.QueryType;
import org.apache.drill.exec.proto.helper.QueryIdHelper;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.rpc.user.ConnectionThrottle;
import org.apache.drill.exec.rpc.user.QueryDataBatch;
import org.apache.drill.exec.rpc.user.UserResultsListener;
import org.apache.drill.jdbc.AlreadyClosedSqlException;
import org.apache.drill.jdbc.DrillConnection;
import org.apache.drill.jdbc.DrillResultSet;
import org.apache.drill.jdbc.ExecutionCanceledSqlException;
import org.apache.drill.jdbc.SchemaChangeListener;

import static org.slf4j.LoggerFactory.getLogger;
import org.slf4j.Logger;

import com.google.common.collect.Queues;


/**
 * Drill's implementation of {@link ResultSet}.
 */
class DrillResultSetImpl extends AvaticaResultSet implements DrillResultSet {
  @SuppressWarnings("unused")
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(DrillResultSetImpl.class);

  private final DrillConnectionImpl connection;

  SchemaChangeListener changeListener;
  final ResultsListener resultsListener;
  private final DrillClient client;
  // TODO:  Resolve:  Since is barely manipulated here in DrillResultSetImpl,
  //  move down into DrillCursor and have this.clean() have cursor clean it.
  final RecordBatchLoader batchLoader;
  final DrillCursor cursor;
  boolean hasPendingCancelationNotification;


  DrillResultSetImpl(AvaticaStatement statement, AvaticaPrepareResult prepareResult,
                     ResultSetMetaData resultSetMetaData, TimeZone timeZone) {
    super(statement, prepareResult, resultSetMetaData, timeZone);
    connection = (DrillConnectionImpl) statement.getConnection();
    client = connection.getClient();
    final int batchQueueThrottlingThreshold =
        client.getConfig().getInt(
            ExecConstants.JDBC_BATCH_QUEUE_THROTTLING_THRESHOLD );
    resultsListener = new ResultsListener(batchQueueThrottlingThreshold);
    batchLoader = new RecordBatchLoader(client.getAllocator());
    cursor = new DrillCursor(this);
  }

  /**
   * Throws AlreadyClosedSqlException or QueryCanceledSqlException if this
   * ResultSet is closed.
   *
   * @throws  ExecutionCanceledSqlException  if ResultSet is closed because of
   *          cancelation and no QueryCanceledSqlException has been thrown yet
   *          for this ResultSet
   * @throws  AlreadyClosedSqlException  if ResultSet is closed
   * @throws  SQLException  if error in calling {@link #isClosed()}
   */
  private void checkNotClosed() throws SQLException {
    if ( isClosed() ) {
      if ( hasPendingCancelationNotification ) {
        hasPendingCancelationNotification = false;
        throw new ExecutionCanceledSqlException(
            "SQL statement execution canceled; ResultSet now closed." );
      }
      else {
        throw new AlreadyClosedSqlException( "ResultSet is already closed." );
      }
    }
  }

  @Override
  protected void cancel() {
    hasPendingCancelationNotification = true;
    cleanup();
    close();
  }

  synchronized void cleanup() {
    if (resultsListener.getQueryId() != null && ! resultsListener.completed) {
      client.cancelQuery(resultsListener.getQueryId());
    }
    resultsListener.close();
    batchLoader.clear();
  }

  ////////////////////////////////////////
  // ResultSet-defined methods (in same order as in ResultSet):

  // (Not delegated.)
  @Override
  public boolean next() throws SQLException {
    checkNotClosed();
    // TODO:  Resolve following comments (possibly obsolete because of later
    // addition of preceding call to checkNotClosed.  Also, NOTE that the
    // following check, and maybe some checkNotClosed() calls, probably must
    // synchronize on the statement, per the comment on AvaticaStatement's
    // openResultSet:

    // Next may be called after close has been called (for example after a user
    // cancellation) which in turn sets the cursor to null.  So we must check
    // before we call next.
    // TODO: handle next() after close is called in the Avatica code.
    if (super.cursor != null) {
      return super.next();
    } else {
      return false;
    }
  }

  @Override
  public void close() {
    // Note:  No already-closed exception for close().
    super.close();
  }

  @Override
  public boolean wasNull() throws SQLException {
    checkNotClosed();
    return super.wasNull();
  }

  // Methods for accessing results by column index
  @Override
  public String getString( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getString( columnIndex );
  }

  @Override
  public boolean getBoolean( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getBoolean( columnIndex );
  }

  @Override
  public byte getByte( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getByte( columnIndex );
  }

  @Override
  public short getShort( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getShort( columnIndex );
  }

  @Override
  public int getInt( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getInt( columnIndex );
  }

  @Override
  public long getLong( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getLong( columnIndex );
  }

  @Override
  public float getFloat( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getFloat( columnIndex );
  }

  @Override
  public double getDouble( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getDouble( columnIndex );
  }

  @Override
  public BigDecimal getBigDecimal( int columnIndex, int scale ) throws SQLException {
    checkNotClosed();
    return super.getBigDecimal( columnIndex, scale );
  }

  @Override
  public byte[] getBytes( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getBytes( columnIndex );
  }

  @Override
  public Date getDate( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getDate( columnIndex );
  }

  @Override
  public Time getTime( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getTime( columnIndex );
  }

  @Override
  public Timestamp getTimestamp( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getTimestamp( columnIndex );
  }

  @Override
  public InputStream getAsciiStream( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getAsciiStream( columnIndex );
  }

  @Override
  public InputStream getUnicodeStream( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getUnicodeStream( columnIndex );
  }

  @Override
  public InputStream getBinaryStream( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getBinaryStream( columnIndex );
  }

  // Methods for accessing results by column label
  @Override
  public String getString( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getString( columnLabel );
  }

  @Override
  public boolean getBoolean( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getBoolean( columnLabel );
  }

  @Override
  public byte getByte( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getByte( columnLabel );
  }

  @Override
  public short getShort( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getShort( columnLabel );
  }

  @Override
  public int getInt( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getInt( columnLabel );
  }

  @Override
  public long getLong( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getLong( columnLabel );
  }

  @Override
  public float getFloat( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getFloat( columnLabel );
  }

  @Override
  public double getDouble( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getDouble( columnLabel );
  }

  @Override
  public BigDecimal getBigDecimal( String columnLabel, int scale ) throws SQLException {
    checkNotClosed();
    return super.getBigDecimal( columnLabel, scale );
  }

  @Override
  public byte[] getBytes( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getBytes( columnLabel );
  }

  @Override
  public Date getDate( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getDate( columnLabel );
  }

  @Override
  public Time getTime( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getTime( columnLabel );
  }

  @Override
  public Timestamp getTimestamp( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getTimestamp( columnLabel );
  }

  @Override
  public InputStream getAsciiStream( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getAsciiStream( columnLabel );
  }

  @Override
  public InputStream getUnicodeStream( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getUnicodeStream( columnLabel );
  }

  @Override
  public InputStream getBinaryStream( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getBinaryStream( columnLabel );
  }

  // Advanced features:
  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkNotClosed();
    return super.getWarnings();
  }

  @Override
  public void clearWarnings() throws SQLException {
    checkNotClosed();
      super.clearWarnings();
  }

  @Override
  public String getCursorName() throws SQLException {
    checkNotClosed();
    return super.getCursorName();
  }

  // (Not delegated.)
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkNotClosed();
    return super.getMetaData();
  }

  @Override
  public Object getObject( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnIndex );
  }

  @Override
  public Object getObject( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnLabel );
  }

  //----------------------------------------------------------------
  @Override
  public int findColumn( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.findColumn( columnLabel );
  }

  //--------------------------JDBC 2.0-----------------------------------
  //---------------------------------------------------------------------
  // Getters and Setters
  //---------------------------------------------------------------------
  @Override
  public Reader getCharacterStream( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getCharacterStream( columnIndex );
  }

  @Override
  public Reader getCharacterStream( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getCharacterStream( columnLabel );
  }

  @Override
  public BigDecimal getBigDecimal( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getBigDecimal( columnIndex );
  }

  @Override
  public BigDecimal getBigDecimal( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getBigDecimal( columnLabel );
  }

  //---------------------------------------------------------------------
  // Traversal/Positioning
  //---------------------------------------------------------------------
  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkNotClosed();
    return super.isBeforeFirst();
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    checkNotClosed();
    return super.isAfterLast();
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkNotClosed();
    return super.isFirst();
  }

  @Override
  public boolean isLast() throws SQLException {
    checkNotClosed();
    return super.isLast();
  }

  @Override
  public void beforeFirst() throws SQLException {
    checkNotClosed();
    super.beforeFirst();
  }

  @Override
  public void afterLast() throws SQLException {
    checkNotClosed();
    super.afterLast();
  }

  @Override
  public boolean first() throws SQLException {
    checkNotClosed();
    return super.first();
  }

  @Override
  public boolean last() throws SQLException {
    checkNotClosed();
    return super.last();
  }

  @Override
  public int getRow() throws SQLException {
    checkNotClosed();
    return super.getRow();
  }

  @Override
  public boolean absolute( int row ) throws SQLException {
    checkNotClosed();
    return super.absolute( row );
  }

  @Override
  public boolean relative( int rows ) throws SQLException {
    checkNotClosed();
    return super.relative( rows );
  }

  @Override
  public boolean previous() throws SQLException {
    checkNotClosed();
    return super.previous();
  }

  //---------------------------------------------------------------------
  // Properties
  //---------------------------------------------------------------------

  @Override
  public void setFetchDirection( int direction ) throws SQLException {
    checkNotClosed();
    super.setFetchDirection( direction );
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkNotClosed();
    return super.getFetchDirection();
  }

  @Override
  public void setFetchSize( int rows ) throws SQLException {
    checkNotClosed();
    super.setFetchSize( rows );
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkNotClosed();
    return super.getFetchSize();
  }

  @Override
  public int getType() throws SQLException {
    checkNotClosed();
    return super.getType();
  }

  @Override
  public int getConcurrency() throws SQLException {
    checkNotClosed();
    return super.getConcurrency();
  }

  //---------------------------------------------------------------------
  // Updates
  //---------------------------------------------------------------------
  @Override
  public boolean rowUpdated() throws SQLException {
    checkNotClosed();
    return super.rowUpdated();
  }

  @Override
  public boolean rowInserted() throws SQLException {
    checkNotClosed();
    return super.rowInserted();
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    checkNotClosed();
    return super.rowDeleted();
  }

  @Override
  public void updateNull( int columnIndex ) throws SQLException {
    checkNotClosed();
    super.updateNull( columnIndex );
  }

  @Override
  public void updateBoolean( int columnIndex, boolean x ) throws SQLException {
    checkNotClosed();
    super.updateBoolean( columnIndex, x );
  }

  @Override
  public void updateByte( int columnIndex, byte x ) throws SQLException {
    checkNotClosed();
    super.updateByte( columnIndex, x );
  }

  @Override
  public void updateShort( int columnIndex, short x ) throws SQLException {
    checkNotClosed();
    super.updateShort( columnIndex, x );
  }

  @Override
  public void updateInt( int columnIndex, int x ) throws SQLException {
    checkNotClosed();
    super.updateInt( columnIndex, x );
  }

  @Override
  public void updateLong( int columnIndex, long x ) throws SQLException {
    checkNotClosed();
    super.updateLong( columnIndex, x );
  }

  @Override
  public void updateFloat( int columnIndex, float x ) throws SQLException {
    checkNotClosed();
    super.updateFloat( columnIndex, x );
  }

  @Override
  public void updateDouble( int columnIndex, double x ) throws SQLException {
    checkNotClosed();
    super.updateDouble( columnIndex, x );
  }

  @Override
  public void updateBigDecimal( int columnIndex, BigDecimal x ) throws SQLException {
    checkNotClosed();
    super.updateBigDecimal( columnIndex, x );
  }

  @Override
  public void updateString( int columnIndex, String x ) throws SQLException {
    checkNotClosed();
    super.updateString( columnIndex, x );
  }

  @Override
  public void updateBytes( int columnIndex, byte[] x ) throws SQLException {
    checkNotClosed();
    super.updateBytes( columnIndex, x );
  }

  @Override
  public void updateDate( int columnIndex, Date x ) throws SQLException {
    checkNotClosed();
    super.updateDate( columnIndex, x );
  }

  @Override
  public void updateTime( int columnIndex, Time x ) throws SQLException {
    checkNotClosed();
    super.updateTime( columnIndex, x );
  }

  @Override
  public void updateTimestamp( int columnIndex, Timestamp x ) throws SQLException {
    checkNotClosed();
    super.updateTimestamp( columnIndex, x );
  }

  @Override
  public void updateAsciiStream( int columnIndex, InputStream x, int length ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnIndex, x, length );
  }

  @Override
  public void updateBinaryStream( int columnIndex, InputStream x, int length ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnIndex, x, length );
  }

  @Override
  public void updateCharacterStream( int columnIndex, Reader x, int length ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnIndex, x, length );
  }

  @Override
  public void updateObject( int columnIndex, Object x, int scaleOrLength ) throws SQLException {
    checkNotClosed();
    super.updateObject( columnIndex, x, scaleOrLength );
  }

  @Override
  public void updateObject( int columnIndex, Object x ) throws SQLException {
    checkNotClosed();
    super.updateObject( columnIndex, x );
  }

  @Override
  public void updateNull( String columnLabel ) throws SQLException {
    checkNotClosed();
    super.updateNull( columnLabel );
  }

  @Override
  public void updateBoolean( String columnLabel, boolean x ) throws SQLException {
    checkNotClosed();
    super.updateBoolean( columnLabel, x );
  }

  @Override
  public void updateByte( String columnLabel, byte x ) throws SQLException {
    checkNotClosed();
    super.updateByte( columnLabel, x );
  }

  @Override
  public void updateShort( String columnLabel, short x ) throws SQLException {
    checkNotClosed();
    super.updateShort( columnLabel, x );
  }

  @Override
  public void updateInt( String columnLabel, int x ) throws SQLException {
    checkNotClosed();
    super.updateInt( columnLabel, x );
  }

  @Override
  public void updateLong( String columnLabel, long x ) throws SQLException {
    checkNotClosed();
    super.updateLong( columnLabel, x );
  }

  @Override
  public void updateFloat( String columnLabel, float x ) throws SQLException {
    checkNotClosed();
    super.updateFloat( columnLabel, x );
  }

  @Override
  public void updateDouble( String columnLabel, double x ) throws SQLException {
    checkNotClosed();
    super.updateDouble( columnLabel, x );
  }

  @Override
  public void updateBigDecimal( String columnLabel, BigDecimal x ) throws SQLException {
    checkNotClosed();
    super.updateBigDecimal( columnLabel, x );
  }

  @Override
  public void updateString( String columnLabel, String x ) throws SQLException {
    checkNotClosed();
    super.updateString( columnLabel, x );
  }

  @Override
  public void updateBytes( String columnLabel, byte[] x ) throws SQLException {
    checkNotClosed();
    super.updateBytes( columnLabel, x );
  }

  @Override
  public void updateDate( String columnLabel, Date x ) throws SQLException {
    checkNotClosed();
    super.updateDate( columnLabel, x );
  }

  @Override
  public void updateTime( String columnLabel, Time x ) throws SQLException {
    checkNotClosed();
    super.updateTime( columnLabel, x );
  }

  @Override
  public void updateTimestamp( String columnLabel, Timestamp x ) throws SQLException {
    checkNotClosed();
    super.updateTimestamp( columnLabel, x );
  }

  @Override
  public void updateAsciiStream( String columnLabel, InputStream x, int length ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnLabel, x, length );
  }

  @Override
  public void updateBinaryStream( String columnLabel, InputStream x, int length ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnLabel, x, length );
  }

  @Override
  public void updateCharacterStream( String columnLabel, Reader reader, int length ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnLabel, reader, length );
  }

  @Override
  public void updateObject( String columnLabel, Object x, int scaleOrLength ) throws SQLException {
    checkNotClosed();
    super.updateObject( columnLabel, x, scaleOrLength );
  }

  @Override
  public void updateObject( String columnLabel, Object x ) throws SQLException {
    checkNotClosed();
    super.updateObject( columnLabel, x );
  }

  @Override
  public void insertRow() throws SQLException {
    checkNotClosed();
    super.insertRow();
  }

  @Override
  public void updateRow() throws SQLException {
    checkNotClosed();
    super.updateRow();
  }

  @Override
  public void deleteRow() throws SQLException {
    checkNotClosed();
    super.deleteRow();
  }

  @Override
  public void refreshRow() throws SQLException {
    checkNotClosed();
    super.refreshRow();
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    checkNotClosed();
    super.cancelRowUpdates();
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    checkNotClosed();
    super.moveToInsertRow();
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    checkNotClosed();
    super.moveToCurrentRow();
  }

  @Override
  public AvaticaStatement getStatement() {
    // Note:  No already-closed exception for getStatement().
    return super.getStatement();
  }

  @Override
  public Object getObject( int columnIndex, java.util.Map<String,Class<?>> map ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnIndex, map );
  }

  @Override
  public Ref getRef( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getRef( columnIndex );
  }

  @Override
  public Blob getBlob( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getBlob( columnIndex );
  }

  @Override
  public Clob getClob( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getClob( columnIndex );
  }

  @Override
  public Array getArray( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getArray( columnIndex );
  }

  @Override
  public Object getObject( String columnLabel, java.util.Map<String,Class<?>> map ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnLabel, map );
  }

  @Override
  public Ref getRef( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getRef( columnLabel );
  }

  @Override
  public Blob getBlob( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getBlob( columnLabel );
  }

  @Override
  public Clob getClob( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getClob( columnLabel );
  }

  @Override
  public Array getArray( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getArray( columnLabel );
  }

  @Override
  public Date getDate( int columnIndex, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getDate( columnIndex, cal );
  }

  @Override
  public Date getDate( String columnLabel, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getDate( columnLabel, cal );
  }

  @Override
  public Time getTime( int columnIndex, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getTime( columnIndex, cal );
  }

  @Override
  public Time getTime( String columnLabel, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getTime( columnLabel, cal );
  }

  @Override
  public Timestamp getTimestamp( int columnIndex, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getTimestamp( columnIndex, cal );
  }

  @Override
  public Timestamp getTimestamp( String columnLabel, Calendar cal ) throws SQLException {
    checkNotClosed();
    return super.getTimestamp( columnLabel, cal );
  }

  //-------------------------- JDBC 3.0 ----------------------------------------

  @Override
  public java.net.URL getURL( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getURL( columnIndex );
  }

  @Override
  public java.net.URL getURL( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getURL( columnLabel );
  }

  @Override
  public void updateRef( int columnIndex, Ref x ) throws SQLException {
    checkNotClosed();
    super.updateRef( columnIndex, x );
  }

  @Override
  public void updateRef( String columnLabel, Ref x ) throws SQLException {
    checkNotClosed();
    super.updateRef( columnLabel, x );
  }

  @Override
  public void updateBlob( int columnIndex, Blob x ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnIndex, x );
  }

  @Override
  public void updateBlob( String columnLabel, Blob x ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnLabel, x );
  }

  @Override
  public void updateClob( int columnIndex, Clob x ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnIndex, x );
  }

  @Override
  public void updateClob( String columnLabel, Clob x ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnLabel, x );
  }

  @Override
  public void updateArray( int columnIndex, Array x ) throws SQLException {
    checkNotClosed();
    super.updateArray( columnIndex, x );
  }

  @Override
  public void updateArray( String columnLabel, Array x ) throws SQLException {
    checkNotClosed();
    super.updateArray( columnLabel, x );
  }

  //------------------------- JDBC 4.0 -----------------------------------
  @Override
  public RowId getRowId( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getRowId( columnIndex );
  }

  @Override
  public RowId getRowId( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getRowId( columnLabel );
  }

  @Override
  public void updateRowId( int columnIndex, RowId x ) throws SQLException {
    checkNotClosed();
    super.updateRowId( columnIndex, x );
  }

  @Override
  public void updateRowId( String columnLabel, RowId x ) throws SQLException {
    checkNotClosed();
    super.updateRowId( columnLabel, x );
  }

  @Override
  public int getHoldability() throws SQLException {
    checkNotClosed();
    return super.getHoldability();
  }

  @Override
  public boolean isClosed() throws SQLException {
    // Note:  No already-closed exception for isClosed().
    return super.isClosed();
  }

  @Override
  public void updateNString( int columnIndex, String nString ) throws SQLException {
    checkNotClosed();
    super.updateNString( columnIndex, nString );
  }

  @Override
  public void updateNString( String columnLabel, String nString ) throws SQLException {
    checkNotClosed();
    super.updateNString( columnLabel, nString );
  }

  @Override
  public void updateNClob( int columnIndex, NClob nClob ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnIndex, nClob );
  }

  @Override
  public void updateNClob( String columnLabel, NClob nClob ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnLabel, nClob );
  }

  @Override
  public NClob getNClob( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getNClob( columnIndex );
  }

  @Override
  public NClob getNClob( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getNClob( columnLabel );
  }

  @Override
  public SQLXML getSQLXML( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getSQLXML( columnIndex );
  }

  @Override
  public SQLXML getSQLXML( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getSQLXML( columnLabel );
  }

  @Override
  public void updateSQLXML( int columnIndex, SQLXML xmlObject ) throws SQLException {
    checkNotClosed();
    super.updateSQLXML( columnIndex, xmlObject );
  }

  @Override
  public void updateSQLXML( String columnLabel, SQLXML xmlObject ) throws SQLException {
    checkNotClosed();
    super.updateSQLXML( columnLabel, xmlObject );
  }

  @Override
  public String getNString( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getNString( columnIndex );
  }

  @Override
  public String getNString( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getNString( columnLabel );
  }

  @Override
  public Reader getNCharacterStream( int columnIndex ) throws SQLException {
    checkNotClosed();
    return super.getNCharacterStream( columnIndex );
  }

  @Override
  public Reader getNCharacterStream( String columnLabel ) throws SQLException {
    checkNotClosed();
    return super.getNCharacterStream( columnLabel );
  }

  @Override
  public void updateNCharacterStream( int columnIndex, Reader x, long length ) throws SQLException {
    checkNotClosed();
    super.updateNCharacterStream( columnIndex, x, length );
  }

  @Override
  public void updateNCharacterStream( String columnLabel, Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateNCharacterStream( columnLabel, reader, length );
  }

  @Override
  public void updateAsciiStream( int columnIndex, InputStream x, long length ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnIndex, x, length );
  }

  @Override
  public void updateBinaryStream( int columnIndex, InputStream x, long length ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnIndex, x, length );
  }

  @Override
  public void updateCharacterStream( int columnIndex, Reader x, long length ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnIndex, x, length );
  }

  @Override
  public void updateAsciiStream( String columnLabel, InputStream x, long length ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnLabel, x, length );
  }

  @Override
  public void updateBinaryStream( String columnLabel, InputStream x, long length ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnLabel, x, length );
  }

  @Override
  public void updateCharacterStream( String columnLabel, Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnLabel, reader, length );
  }

  @Override
  public void updateBlob( int columnIndex, InputStream inputStream, long length ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnIndex, inputStream, length );
  }

  @Override
  public void updateBlob( String columnLabel, InputStream inputStream, long length ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnLabel, inputStream, length );
  }

  @Override
  public void updateClob( int columnIndex,  Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnIndex, reader, length );
  }

  @Override
  public void updateClob( String columnLabel,  Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnLabel, reader, length );
  }

  @Override
  public void updateNClob( int columnIndex,  Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnIndex, reader, length );
  }

  @Override
  public void updateNClob( String columnLabel,  Reader reader, long length ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnLabel, reader, length );
  }

  //---
  @Override
  public void updateNCharacterStream( int columnIndex, Reader x ) throws SQLException {
    checkNotClosed();
    super.updateNCharacterStream( columnIndex, x );
  }

  @Override
  public void updateNCharacterStream( String columnLabel, Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateNCharacterStream( columnLabel, reader );
  }

  @Override
  public void updateAsciiStream( int columnIndex, InputStream x ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnIndex, x );
  }

  @Override
  public void updateBinaryStream( int columnIndex, InputStream x ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnIndex, x );
  }

  @Override
  public void updateCharacterStream( int columnIndex, Reader x ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnIndex, x );
  }

  @Override
  public void updateAsciiStream( String columnLabel, InputStream x ) throws SQLException {
    checkNotClosed();
    super.updateAsciiStream( columnLabel, x );
  }

  @Override
  public void updateBinaryStream( String columnLabel, InputStream x ) throws SQLException {
    checkNotClosed();
    super.updateBinaryStream( columnLabel, x );
  }

  @Override
  public void updateCharacterStream( String columnLabel, Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateCharacterStream( columnLabel, reader );
  }

  @Override
  public void updateBlob( int columnIndex, InputStream inputStream ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnIndex, inputStream );
  }

  @Override
  public void updateBlob( String columnLabel, InputStream inputStream ) throws SQLException {
    checkNotClosed();
    super.updateBlob( columnLabel, inputStream );
  }

  @Override
  public void updateClob( int columnIndex,  Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnIndex, reader );
  }

  @Override
  public void updateClob( String columnLabel,  Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateClob( columnLabel, reader );
  }

  @Override
  public void updateNClob( int columnIndex,  Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnIndex, reader );
  }

  @Override
  public void updateNClob( String columnLabel,  Reader reader ) throws SQLException {
    checkNotClosed();
    super.updateNClob( columnLabel, reader );
  }

  //------------------------- JDBC 4.1 -----------------------------------
  @Override
  public <T> T getObject( int columnIndex, Class<T> type ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnIndex, type );
  }

  @Override
  public <T> T getObject( String columnLabel, Class<T> type ) throws SQLException {
    checkNotClosed();
    return super.getObject( columnLabel, type );
  }


  ////////////////////////////////////////
  // DrillResultSet methods:

  public String getQueryId() throws SQLException {
    checkNotClosed();
    if (resultsListener.getQueryId() != null) {
      return QueryIdHelper.getQueryId(resultsListener.getQueryId());
    } else {
      return null;
    }
  }


  ////////////////////////////////////////

  @Override
  protected DrillResultSetImpl execute() throws SQLException{
    client.runQuery(QueryType.SQL, this.prepareResult.getSql(), resultsListener);
    connection.getDriver().handler.onStatementExecute(statement, null);

    super.execute();

    // don't return with metadata until we've achieved at least one return message.
    try {
      // TODO:  Revisit:  Why reaching directly into ResultsListener rather than
      // calling some wait method?
      resultsListener.latch.await();
    } catch ( InterruptedException e ) {
      // Preserve evidence that the interruption occurred so that code higher up
      // on the call stack can learn of the interruption and respond to it if it
      // wants to.
      Thread.currentThread().interrupt();

      // Not normally expected--Drill doesn't interrupt in this area (right?)--
      // but JDBC client certainly could.
      throw new SQLException( "Interrupted", e );
    }

    // Read first (schema-only) batch to initialize result-set metadata from
    // (initial) schema before Statement.execute...(...) returns result set:
    cursor.loadInitialSchema();

    return this;
  }


  ////////////////////////////////////////
  // ResultsListener:

  static class ResultsListener implements UserResultsListener {
    private static final Logger logger = getLogger( ResultsListener.class );

    private static volatile int nextInstanceId = 1;

    /** (Just for logging.) */
    private final int instanceId;

    private final int batchQueueThrottlingThreshold;

    /** (Just for logging.) */
    private volatile QueryId queryId;

    /** (Just for logging.) */
    private int lastReceivedBatchNumber;
    /** (Just for logging.) */
    private int lastDequeuedBatchNumber;

    private volatile UserException executionFailureException;

    // TODO:  Revisit "completed".  Determine and document exactly what it
    // means.  Some uses imply that it means that incoming messages indicate
    // that the _query_ has _terminated_ (not necessarily _completing_
    // normally), while some uses imply that it's some other state of the
    // ResultListener.  Some uses seem redundant.)
    volatile boolean completed = false;

    /** Whether throttling of incoming data is active. */
    private final AtomicBoolean throttled = new AtomicBoolean( false );
    private volatile ConnectionThrottle throttle;

    private volatile boolean closed = false;
    // TODO:  Rename.  It's obvious it's a latch--but what condition or action
    // does it represent or control?
    private CountDownLatch latch = new CountDownLatch(1);
    private AtomicBoolean receivedMessage = new AtomicBoolean(false);

    final LinkedBlockingDeque<QueryDataBatch> batchQueue =
        Queues.newLinkedBlockingDeque();


    /**
     * ...
     * @param  batchQueueThrottlingThreshold
     *         queue size threshold for throttling server
     */
    ResultsListener( int batchQueueThrottlingThreshold ) {
      instanceId = nextInstanceId++;
      this.batchQueueThrottlingThreshold = batchQueueThrottlingThreshold;
      logger.debug( "[#{}] Query listener created.", instanceId );
    }

    /**
     * Starts throttling if not currently throttling.
     * @param  throttle  the "throttlable" object to throttle
     * @return  true if actually started (wasn't throttling already)
     */
    private boolean startThrottlingIfNot( ConnectionThrottle throttle ) {
      final boolean started = throttled.compareAndSet( false, true );
      if ( started ) {
        this.throttle = throttle;
        throttle.setAutoRead(false);
      }
      return started;
    }

    /**
     * Stops throttling if currently throttling.
     * @return  true if actually stopped (was throttling)
     */
    private boolean stopThrottlingIfSo() {
      final boolean stopped = throttled.compareAndSet( true, false );
      if ( stopped ) {
        throttle.setAutoRead(true);
        throttle = null;
      }
      return stopped;
    }

    // TODO:  Doc.:  Release what if what is first relative to what?
    private boolean releaseIfFirst() {
      if (receivedMessage.compareAndSet(false, true)) {
        latch.countDown();
        return true;
      }

      return false;
    }

    @Override
    public void queryIdArrived(QueryId queryId) {
      logger.debug( "[#{}] Received query ID: {}.",
                    instanceId, QueryIdHelper.getQueryId( queryId ) );
      this.queryId = queryId;
    }

    @Override
    public void submissionFailed(UserException ex) {
      logger.debug( "Received query failure:", instanceId, ex );
      this.executionFailureException = ex;
      completed = true;
      close();
      logger.info( "[#{}] Query failed: ", instanceId, ex );
    }

    @Override
    public void dataArrived(QueryDataBatch result, ConnectionThrottle throttle) {
      lastReceivedBatchNumber++;
      logger.debug( "[#{}] Received query data batch #{}: {}.",
                    instanceId, lastReceivedBatchNumber, result );

      // If we're in a closed state, just release the message.
      if (closed) {
        result.release();
        // TODO:  Revisit member completed:  Is ResultListener really completed
        // after only one data batch after being closed?
        completed = true;
        return;
      }

      // We're active; let's add to the queue.
      batchQueue.add(result);

      // Throttle server if queue size has exceed threshold.
      if (batchQueue.size() > batchQueueThrottlingThreshold ) {
        if ( startThrottlingIfNot( throttle ) ) {
          logger.debug( "[#{}] Throttling started at queue size {}.",
                        instanceId, batchQueue.size() );
        }
      }

      releaseIfFirst();
    }

    @Override
    public void queryCompleted(QueryState state) {
      logger.debug( "[#{}] Received query completion: {}.", instanceId, state );
      releaseIfFirst();
      completed = true;
    }

    QueryId getQueryId() {
      return queryId;
    }


    /**
     * Gets the next batch of query results from the queue.
     * @return  the next batch, or {@code null} after last batch has been returned
     * @throws UserException
     *         if the query failed
     * @throws InterruptedException
     *         if waiting on the queue was interrupted
     */
    QueryDataBatch getNext() throws UserException, InterruptedException {
      while (true) {
        if (executionFailureException != null) {
          logger.debug( "[#{}] Dequeued query failure exception: {}.",
                        instanceId, executionFailureException );
          throw executionFailureException;
        }
        if (completed && batchQueue.isEmpty()) {
          return null;
        } else {
          QueryDataBatch qdb = batchQueue.poll(50, TimeUnit.MILLISECONDS);
          if (qdb != null) {
            lastDequeuedBatchNumber++;
            logger.debug( "[#{}] Dequeued query data batch #{}: {}.",
                          instanceId, lastDequeuedBatchNumber, qdb );

            // Unthrottle server if queue size has dropped enough below threshold:
            if ( batchQueue.size() < batchQueueThrottlingThreshold / 2
                 || batchQueue.size() == 0  // (in case threshold < 2)
                 ) {
              if ( stopThrottlingIfSo() ) {
                logger.debug( "[#{}] Throttling stopped at queue size {}.",
                              instanceId, batchQueue.size() );
              }
            }
            return qdb;
          }
        }
      }
    }

    void close() {
      logger.debug( "[#{}] Query listener closing.", instanceId );
      closed = true;
      if ( stopThrottlingIfSo() ) {
        logger.debug( "[#{}] Throttling stopped at close() (at queue size {}).",
                      instanceId, batchQueue.size() );
      }
      while (!batchQueue.isEmpty()) {
        QueryDataBatch qdb = batchQueue.poll();
        if (qdb != null && qdb.getData() != null) {
          qdb.getData().release();
        }
      }
      // Close may be called before the first result is received and therefore
      // when the main thread is blocked waiting for the result.  In that case
      // we want to unblock the main thread.
      latch.countDown(); // TODO:  Why not call releaseIfFirst as used elsewhere?
      completed = true;
    }

  }

}
