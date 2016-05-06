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

package com.justone.kafkapgjsonsink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.connector.ConnectorContext;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;

/**
 * Kafka sink connector for PostgreSQL
 * 
 * @author Duncan Pauly
 * @version 1.0
 * 
 */
public class PostgreSQLSinkConnector extends SinkConnector {
  /**
   * Version of the connector
   */
  public final static String VERSION="1.0a"; 
  /**
   * Configuration properties for the connector
   */
  private Map<String,String> fProperties;
  
  /**
   * Returns version of the connector
   * @return version
   */
  @Override
  public String version() {
    
      return VERSION;//return version
      
  }//version()

  /**
   * Initialise the connector
   * @param ctx context of the connector
   */
  @Override
  public void initialize(ConnectorContext ctx) {
    //do nothing    
  }//initialize()
  
  /**
   * Initialise the connector
   * @param ctx context of the connector
   * @param taskConfigs task configuration
   */
  @Override
  public void initialize(ConnectorContext ctx,
              List<Map<String,String>> taskConfigs) {
    //do nothing
  }//initialize() 

  /**
   * Start the connector
   * @param props connector configuration properties
   */
  @Override
  public void start(Map<String, String> props) {
    
    fProperties=props;//set connector configuration properties
    
  }//start()

  /**
   * Stop the connector
   */
  @Override
  public void stop() {
    //do nothing
  }//stop()
  
  /**
   * Returns class of task
   * @return class of task
   */
   @Override
  public Class<? extends Task> taskClass() {
      return PostgreSQLSinkTask.class;//return task class
  }//taskClass()

  /**
   * Returns task configurations
   * @param maxTasks maximum tasks to execute
   * @return task configurations
   */
  @Override
  public List<Map<String, String>> taskConfigs(int maxTasks) {
    
      ArrayList<Map<String, String>> configurations = new ArrayList<>();//construct list
      
      for (int i = 0; i < maxTasks; i++) {//for each task
        configurations.add(fProperties);//add connector configuration
      }//for each task
      
      return configurations;//return task configurations
      
  }//taskConfigs()

}//PostgreSQLSinkConnector{}
