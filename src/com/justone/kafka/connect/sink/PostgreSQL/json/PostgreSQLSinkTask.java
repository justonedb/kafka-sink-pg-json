/*

MIT License
 
Copyright (c) 2016 JustOne Database Inc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
package com.justone.kafka.connect.sink.PostgreSQL.json;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.HashMap;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.justone.tablewriter.TableWriter;

import com.justone.json.Element;
import com.justone.json.Parser;
import com.justone.json.Path;

/**
 * Task for PostgreSQL sink connector
 * @author Duncan Pauly
 * @version 1.0
 */
public class PostgreSQLSinkTask extends SinkTask {
  
  /**
   * Fastest delivery semantic
   */
  private static final int FASTEST = 0;
  /**
   * Guaranteed delivery semantic
   */
  private static final int GUARANTEED = 1;
  /**
   * Synchronised delivery semantic
   */
  private static final int SYNCHRONIZED = 2;
  /**
   * Delivery configuration options
   */
  private static final String[] DELIVERY=new String[]{"FASTEST","GUARANTEED","SYNCHRONIZED"};
   /**
   * Database host server property key
   */
  public static final String HOST_CONFIG = "db.host";
  /**
   * Database name property key
   */
  public static final String DATABASE_CONFIG = "db.database";
  /**
   * Database username property key
   */
  public static final String USER_CONFIG = "db.username";
  /**
   * Database password property key
   */
  public static final String PASSWORD_CONFIG = "db.password";
  /**
   * Schema name property key
   */
  public static final String SCHEMA_CONFIG = "db.schema";
  /**
   * Table name property key
   */
  public static final String TABLE_CONFIG = "db.table";
  /**
   * Table column names property key
   */
  public static final String COLUMN_CONFIG = "db.columns";
  /**
   * Delivery semantics property key
   */
  public static final String DELIVERY_CONFIG = "db.delivery";
  /**
   * JSON parse paths property key
   */
  public static final String PARSE_CONFIG = "db.json.parse";
  /**
   * Buffer size property key
   */
  public static final String BUFFER_CONFIG = "db.buffer.size";
  /**
   * Synchronise command to start sink task
   */
  private static final String SYNC_START = "SELECT \"$justone$kafka$connect$sink\".start('<S>','<T>')";
  /**
   * Synchronise command to get synchronisation state
   */
  private static final String SYNC_STATE = "SELECT kafkaTopic,kafkaPartition,kafkaOffset FROM \"$justone$kafka$connect$sink\".state('<S>','<T>')";
  /**
   * Synchronise command to flush
   */
  private static final String SYNC_FLUSH = "SELECT \"$justone$kafka$connect$sink\".flush('<S>','<T>',?,?,?)";
  /**
   * Synchronise command to drop synchronisation state
   */
  private static final String SYNC_DROP = "SELECT \"$justone$kafka$connect$sink\".drop('<S>','<T>')";
  /**
   * Logger for trace messages
   */
  private static final Logger fLog = LoggerFactory.getLogger(PostgreSQLSinkTask.class);
  /**
   * Sink task context
   */
  private SinkTaskContext iTaskContext;
  /**
   * Table writer for appending to the table
   */
  private TableWriter iWriter;
  /**
   * Paths for JSON parsing
   */
  private Path[] iPaths;
  /**
   * Parser for JSON parsing
   */
  private Parser iParser;
  /**
   * Delivery semantic 
   */
  private int iDelivery;
  /**
   * Database connection
   */
  private Connection iConnection;
  /**
   * Sink table flush statement
   */
  private PreparedStatement iFlushStatement;
  
  /**
   * Constructor for sink task
   */
  public PostgreSQLSinkTask() {
  }//PostgreSQLSinkTask()

  /**
   * Return connector version
   * @return version string
   */
  @Override
  public String version() {
    
    return PostgreSQLSinkConnector.VERSION;//return connector version
    
  }//version()

  /**
   * Initialise sink task
   * @param context context of the sink task
   */
  @Override
  public void initialize(SinkTaskContext context) {
    
    iTaskContext=context;//save task context
    
  }//initialize()
  
  /**
   * Start the task
   * @param props configuration properties
   * @throws ConnectException if failed to start
   */
  @Override
  public void start(Map<String, String> props) throws ConnectException {
    
    fLog.trace("Starting");
    fLog.info("Sink connector config: ", props);

    try {

      /* get configuration properties */
      String host=props.get(HOST_CONFIG);//database host
      String database=props.get(DATABASE_CONFIG);//database name
      String username=props.get(USER_CONFIG);//database username
      String password=props.get(PASSWORD_CONFIG);//database password
      String schema=props.get(SCHEMA_CONFIG);//schema of table to sink to
      String table=props.get(TABLE_CONFIG);//name of table to sink to
      String columnList=props.get(COLUMN_CONFIG);//columns to sink to
      Integer bufferSize=Integer.parseInt(props.get(BUFFER_CONFIG));//task buffer size
      String pathList=props.get(PARSE_CONFIG);//list if JSON parse paths
      String delivery=props.get(DELIVERY_CONFIG);//delivery semantics required 

      /* validate configuration */
      if (database==null) throw new ConnectException("Database not configured");//database name is mandatory
      if (schema==null) throw new ConnectException("Schema not configured");//schema name is mandatory
      if (table==null) throw new ConnectException("Table not configured");//table name is mandatory
      if (columnList==null) throw new ConnectException("Columns not configured");//column list is mandatory
      if (pathList==null) throw new ConnectException("Parse paths not configured");//path list is mandatory
      if (bufferSize<0) throw new ConnectException("Buffer size configuration is invalid");//buffer size is mandatory
      
      /* construct parse paths from path list */
      String[] columns=columnList.split("\\,");//split column list into separate strings
      String[] paths=pathList.split("\\,");//split path list into separate strings
      iPaths=new Path[paths.length];//construct array of paths
      for (int i=0;i<paths.length;++i) {//for each path 
        iPaths[i]=new Path(paths[i]);//construct path from path string
      }//for each path 
      if (iPaths.length!=(columns.length)) throw new ConnectException("Number of parse paths must match number of table columns");//parse paths must match column names

      iDelivery=SYNCHRONIZED;//default delivery is fully synchronized
      if (delivery!=null) {//if delivery option specified
        for (int i=0;i<DELIVERY.length;++i) {//for each delivery option
          if (delivery.equalsIgnoreCase(DELIVERY[i])) iDelivery=i;
        }//for each delivery option
      }//if delivery option specified
      
      iWriter=new TableWriter(host,database,username,password,table,columns,bufferSize);//construct table writer
        
      iConnection=iWriter.getConnection();
      Statement statement=iConnection.createStatement();
      
      if (iDelivery==SYNCHRONIZED) {//if synchonized delivery

        /* start sink session */
        String start=SYNC_START.replace("<S>",schema).replace("<T>",table);//prepare start statement
        statement.executeQuery(start);//perform start
        
        /* fetch table state */
        String state=SYNC_STATE.replace("<S>",schema).replace("<T>",table);//prepare state query statement
        ResultSet resultSet=statement.executeQuery(state);//perform state query
        
        if (resultSet.isBeforeFirst()) {//if state is not empty
          HashMap<TopicPartition,Long> offsetMap=new HashMap<>();//construct map of offsets
          while (resultSet.next()) {//for each state row
            String topic=resultSet.getString(1);//get topic
            Integer partition=resultSet.getInt(2);//get partition number
            Long offset=resultSet.getLong(3)+1;//get offset number
            offsetMap.put(new TopicPartition(topic,partition),offset);//append to map of offsets           
          }//for each partition
          resultSet.close();//be a good citizen

          iTaskContext.offset(offsetMap);//synchronise offsets
          
        }//if state is not empty
        
        /* prepare flush statement */
        String flush=SYNC_FLUSH.replace("<S>",schema).replace("<T>",table);//prepare flush statement
        iFlushStatement=iConnection.prepareStatement(flush);//set flush statement
      
      } else {//else non synchronised delivery
        
        /* drop synchronization state */
        String drop=SYNC_DROP.replace("<S>",schema).replace("<T>",table);//prepare drop statement
        statement.executeQuery(drop);//perform drop

      }//if synchonized delivery
      
      iParser=new Parser();//construct parser
    
    } catch (NumberFormatException | SQLException | IOException exception) {
      throw new ConnectException(exception);//ho hum...
    }//try{}
    
  }//start()

  /**
   * Parses JSON value in each record and appends JSON elements to the table
   * @param sinkRecords records to be written
   * @throws ConnectException if put fails
   */
  @Override
  public void put(Collection<SinkRecord> sinkRecords) throws ConnectException {
    
    for (SinkRecord record : sinkRecords) {//for each sink record
      
      fLog.trace("Put message {}", record.value());
      
      try {
        
        iParser.parse(record.value().toString());//parse record value
        
        /* append parsed JSON elements to the table */
        for (int i=0;i<iPaths.length;++i) {//for each parse path
          
          Element element=iParser.getElement(iPaths[i]);//extract element at path
          
          if (element==null) { //if no element found
            //append nothing
          } else {//else element found
            
            String string=element.toString();//convert element to string
            
            if (string.equals("null")) {//if "null" string
               //append nothing
            } else {//else other than "null" string
              
              if (string.charAt(0)=='"')//if enclosed in quotes
                iWriter.append(string.substring(1,string.length()-1));//append string without quotation characters
              else//else not enclosed in quotes
                iWriter.append(string);//append string value
              
            }//if "null" string
            
          }//if no element found
          
          iWriter.next();//advance to the next column
          
        }//for each element
                
      } catch (IOException exception) {
        throw new ConnectException(exception);
      }//try{}
      
    }//for each sink record
    
  }//put()

  /**
   * Flushes content to the database
   * @param offsets map of offsets being flushed
   * @throws ConnectException if flush failed
   */
  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) throws ConnectException {
    
      fLog.trace("Flush start at "+System.currentTimeMillis());
      
      try {
        
        if (iDelivery>FASTEST)//if guaranteed or synchronized
          iWriter.flush();//flush table writes
        
        if (iDelivery==SYNCHRONIZED) {//if synchronized delivery
          
          /* create topic, partition and offset arrays for database flush function call */
          
          int size=offsets.size();//get number of flush map entries
          String[] topicArray=new String[size];//create array for topics
          Integer[] partitionArray=new Integer[size];//create array for partitions 
          Long[] offsetArray=new Long[size];//create array for offsets

          /* populate topic, partition and offset arrays */
          
          Iterator<Map.Entry<TopicPartition, OffsetAndMetadata>> iterator=offsets.entrySet().iterator();//create map iterator
          for (int i=0;i<size;++i) {//for each flush map entry
            Entry<TopicPartition, OffsetAndMetadata> entry=iterator.next();//get next entry
            TopicPartition key=entry.getKey();//get topic partition key
            OffsetAndMetadata value=entry.getValue();//get offset value
            topicArray[i]=key.topic();//put topic into array
            partitionArray[i]=key.partition();//put partition in to array
            offsetArray[i]=value.offset();//put offset into array                        
          }//for each flush map entry

          /* bind arays to flush statement */
          
          iFlushStatement.setArray(1, iConnection.createArrayOf("varchar", topicArray));//bind topic array
          iFlushStatement.setArray(2, iConnection.createArrayOf("integer", partitionArray));//bind partition array
          iFlushStatement.setArray(3, iConnection.createArrayOf("bigint", offsetArray));//bind offset array
          
          /* execute the database flush function */
          
          iFlushStatement.executeQuery();
          
        }//if synchronized delivery
        
      } catch (SQLException | IOException exception) {
        throw new ConnectException(exception);
      }//try{}
      
      fLog.trace("Flush stop at "+System.currentTimeMillis());
       
  }//flush()

  /**
   * Stops the sink task
   * @throws ConnectException 
   */
  @Override
  public void stop() throws ConnectException {
    
    fLog.trace("Stopping");
    
    try {
      
      iWriter.close();//close table writer
      
    } catch (IOException exception) {
      throw new ConnectException(exception);
    }//try{}
    
  }//stop()

}//PostgreSQLSinkTask
