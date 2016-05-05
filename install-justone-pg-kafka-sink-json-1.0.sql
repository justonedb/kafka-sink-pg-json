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

--------------------------------------------------------------------------------------------------------------------------------
--
-- Installs the package for Kafka synchronized delivery used by JustOne Kafka PostgreSQL sink connectors.
--
-- The package provides the functions for starting, flushing and getting state to ensure synchronized delivery semantics (exactly once) 
-- from Kafka to the sink table.
--
-- The functions and state information are stored in the "$justone$kafka$connect$sink" schema. Within this schema, each sink table has a 
-- corresponding state table called "<s>.<t>" where <s> and <t> are the schema and name of the sink table respectively. 
-- A state table contains a row for each topic, partition and offset.
--
-- The start() function is called when a Kafka sink task is started. It creates a temporary sink table and also creates a Kafka 
-- state table if it does not already exist. The sink task can then insert rows into the temporary sink table.
--
-- The state() function returns rows from the state table for a specified sink table. This information can be used by the sink 
-- connector to initialise offsets for synchronizing consumption with the table state. Note that this function may return no
-- rows if the sink table has not been flushed. 
--
-- The flush() function is called during a sink task flush. It copies rows from the temporary sink table to the permanent sink table
-- and refreshes the Kafka state information in the state table. This is performed in the same transaction to guarantee 
-- synchronization.
--
-- The drop() function is called to drop synchronization state if non synchronized delivery is used by the sink task.
--
-- Process flow is typically:
--
--   SELECT "$justone$kafka$connect$sink".start(<schema>,<table>);
--   SELECT kafkaTopic,kafkaPartition,kafkaOffset FROM "$justone$kafka$connect$sink".state(<schema>,<table>);
--   insert rows into temporay sink table
--   SELECT "$justone$kafka$connect$sink".flush(<schema>,<table>,<topics>,<partitions>,<offsets>);
--
--------------------------------------------------------------------------------------------------------------------------------
--
-- Version History
-- Version 1.0, 20 April 2016, Duncan Pauly
-- 
--------------------------------------------------------------------------------------------------------------------------------

--------------------------------------------------------------------------------------------------------------------------------
-- Schema for Kafka synchronisation package
--------------------------------------------------------------------------------------------------------------------------------
DROP SCHEMA "$justone$kafka$connect$sink" CASCADE;
CREATE SCHEMA "$justone$kafka$connect$sink";

--------------------------------------------------------------------------------------------------------------------------------
--
-- Function to start task for a sink table
--
-- Parameters:
--
--   schemaName - schema of sink table
--   tableName - name of sink table
--
--------------------------------------------------------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "$justone$kafka$connect$sink".start(VARCHAR,VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION "$justone$kafka$connect$sink".start(schemaName VARCHAR, tableName VARCHAR) RETURNS VOID AS $$
BEGIN

  /* create tempporary sink table */
  EXECUTE format('CREATE TEMPORARY TABLE %2$s AS SELECT * FROM "%1$s"."%2$s" WHERE FALSE',schemaName,tableName); 

  /* create state table if it does not exist */
  EXECUTE format('CREATE TABLE IF NOT EXISTS "$justone$kafka$connect$sink"."%1$s.%2$s" (kafka_topic VARCHAR NOT NULL, kafka_partition INTEGER NOT NULL, kafka_offset BIGINT NOT NULL) TABLESPACE pg_default', schemaName, tableName);

  RETURN;

END;
$$ LANGUAGE plpgsql;

--------------------------------------------------------------------------------------------------------------------------------
--
-- Function to flush sink table state
--
-- Parameters:
--
--   schemaName - schema of sink table being flushed
--   tableName - name of sink table being flushed
--   kafkaTopics - array of Kafka topic states
--   kafkaPartitions - array of Kafka partition states
--   kafkaOffsets - array of Kafka offset states
--
--------------------------------------------------------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "$justone$kafka$connect$sink".flush(VARCHAR,VARCHAR,VARCHAR[], INTEGER[], BIGINT[]) CASCADE;
CREATE OR REPLACE FUNCTION "$justone$kafka$connect$sink".flush(schemaName VARCHAR, tableName VARCHAR, kafkaTopics VARCHAR[], kafkaPartitions INTEGER[], kafkaOffsets BIGINT[]) RETURNS VOID AS $$
BEGIN

  /* ensure temporary sink table exists */
  IF (NOT pg_table_is_visible(format('pg_temp."%1$s"',tableName)::regclass)) THEN
    RAISE EXCEPTION 'No temporary sink table';
  END IF;

  /* copy temporary sink table to permanent sink table */
  EXECUTE format('INSERT INTO "%1$s"."%2$s" SELECT * FROM pg_temp."%2$s"', schemaName, tableName);

  /* truncate temporary sink table */
  EXECUTE format('TRUNCATE TABLE pg_temp."%1$s"', tableName);

  /* truncate state table */
  EXECUTE format('TRUNCATE TABLE "$justone$kafka$connect$sink"."%1$s.%2$s"', schemaName, tableName);

  /* insert kafka information into state table */
  EXECUTE format('INSERT INTO "$justone$kafka$connect$sink"."%1$s.%2$s" (kafka_topic, kafka_partition, kafka_offset) SELECT unnest($1),unnest($2),unnest($3)', schemaName, tableName) USING kafkaTopics, kafkaPartitions, kafkaOffsets;

  RETURN;

END;
$$ LANGUAGE plpgsql;

--------------------------------------------------------------------------------------------------------------------------------
--
-- Function to return Kafka state information for a sink table
--
-- Parameters:
--
--   schemaname - schema of sink table to get state information for
--   tablename - name of sink table to get state information for
--
-- Return:
--
--   kafkaTopic - topic state
--   kafkaPartition - partition state
--   kafkaOffset - offset state
--------------------------------------------------------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "$justone$kafka$connect$sink".state(VARCHAR,VARCHAR,VARCHAR[], INTEGER[], BIGINT[]) CASCADE;
CREATE OR REPLACE FUNCTION "$justone$kafka$connect$sink".state(schemaname VARCHAR, tablename VARCHAR) 
RETURNS TABLE (kafkaTopic VARCHAR, kafkaPartition INTEGER, kafkaOffset BIGINT) AS $$
BEGIN

  /* query and return rows from state table */
  RETURN QUERY EXECUTE format('SELECT kafka_topic, kafka_partition, kafka_offset FROM "$justone$kafka$connect$sink"."%1$s.%2$s"',schemaName,tableName);

END;
$$ LANGUAGE plpgsql;

--------------------------------------------------------------------------------------------------------------------------------
--
-- Function to drop synchronization state for a sink table
--
-- Parameters:
--
--   schemaName - schema of sink table
--   tableName - name of sink table
--
--------------------------------------------------------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "$justone$kafka$connect$sink".drop(VARCHAR,VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION "$justone$kafka$connect$sink".drop(schemaName VARCHAR, tableName VARCHAR) RETURNS VOID AS $$
BEGIN

  /* drop state table if it exists */
  EXECUTE format('DROP TABLE IF EXISTS "$justone$kafka$connect$sink"."%1$s.%2$s"', schemaName, tableName);

  RETURN;

END;
$$ LANGUAGE plpgsql;

--------------------------------------------------------------------------------------------------------------------------------
--
-- Grants
--
--------------------------------------------------------------------------------------------------------------------------------
GRANT ALL ON SCHEMA "$justone$kafka$connect$sink" TO public;
GRANT ALL ON ALL FUNCTIONS IN SCHEMA "$justone$kafka$connect$sink" TO public; 