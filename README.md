# kafka-sink-pg-json

## Description

Kafka sink connector for streaming JSON messages into a PostgreSQL table.

The connector receives message values in JSON format which are parsed into column values and writes one row to a table for
each message received.   

The connector provides configuration options for controlling:

* How JSON messages are parsed into relational rows
* Message delivery semantics (at most once, at least once or exactly once).


## Requirements

* Kafka 0.9 or later
* PostgreSQL 9.0 or later (or a compatible database that supports the COPY interface and the pl/pgsql language)

## Components

### SQL files 

* install-justone-kafka-sink-pg-1.0.sql
* uninstall-justone-kafka-sink-pg-1.0.sql

### Property files

* justone-kafka-sink-pg-json-standalone.properties
* justone-kafka-sink-pg-json-connector.properties

### Jar files

* justone-kafka-sink-pg-json-1.0.jar
* justone-json-1.0.jar
* justone-pgwriter-1.0.jar
* postgresql-9.3-1103.jdbc4.jar

## Installation

* Place the jar files in the kafka library directory (libs)
* Place the property files in the kafka configuration directory (config)
* Install the package in the database using \i install-justone-kafka-sink-pg-1.0.sql from a psql session

Note that the package must be installed in each database the connector will be used with. If the package has not been installed in
the database you will see an error of the form ERROR: schema "$justone$kafka$connect$sink" does not exist.

## Uninstall

If you wish to uninstall the package from the database, you can use \i uninstall-justone-kafka-sink-pg-1.0.sql from a psql session

## Usage

Edit the justone-kafka-sink-pg-json-connector.properties file (see below) to configure the behaviour of the sink connector.

To run the connector in standalone mode, use the following command from the Kafka home directory:

bin/connect-standalone.sh config/justone-kafka-sink-pg-json-standalone.properties config/justone-kafka-sink-pg-json-connector.properties

To run the connector in distributed mode, see Kafka documentation.

Please note the following:

* Only the value component of a message is parsed (if an optional key is provided, it is disregarded)
* The connector does not use any schema information and will ignore any associated with a message
* If a message contains no elements which can be reached by the configured parse paths then a row with all null columns is inserted into the table  

Typically, a seperate topic is configured for each table. However, the connector can consume messages from multiple topics, but be aware that a message which does not contain any of the configured parse paths will cause a row with null columns to be inserted.  


## Configuration

### Kafka Connect

The value converter used by Kafka connect should be StringConverter and the following property should be set:

value.converter=org.apache.kafka.connect.storage.StringConverter

This has already been set in the supplied justone-kafka-sink-pg-json-standalone.properties file - but you will likely 
need to modify the setting if using another property file for distributed mode.

### Sink Connector

The sink connector properties (justone-kafka-sink-pg-json-connector.properties) are as follows:

* tasks.max      - number of tasks to be assigned to the connector. Mandatory. Must be 1 or more.
* topics         - topics to consume from. Mandatory.
* db.host        - server address/name of the database host. Optional. Default is localhost.
* db.database    - database to connect to. Mandatory.
* db.username    - username to connect to the database with. Mandatory.
* db.password    - password to use for user authentication. Optional. Default is none.
* db.schema      - schema of the table to append to. Mandatory.
* db.table       - name of the table to append to. Mandatory.
* db.columns     - comma separated list of columns to receive json element values. Mandatory.
* db.json.parse  - comma separated list of parse paths to retrieve json elements by (see below). Mandatory. 
* db.delivery    - type of delivery. Must be one of fastest, guaranteed, synchronized (see below). Optional. Default is synchronized. 
* db.buffer.size - buffer size for caching table writes
 
## Delivery Modes

The connector offers 3 delivery modes:

* Fastest       - a message will be delivered at most once, but may be lost.
* Guaranteed    - a message is guaranteed to be delivered, but may be duplicated.
* Synchronized  - a message is delivered exactly once.

Delivery semantics are controlled by setting the db.delivery property in justone-kafka-sink-pg-json-1.0.properties.

Note that the synchronized mode stores Kafka state in the database and if you subsequently run the connector in a non-synchronized mode
(fastest or guaranteed) then any Kafka state for that table is discarded from the database.  
  
## JSON Parsing

Elements from a JSON message are parsed out into column values and this is specified using a list of parse paths in the db.json.parse property.
Each parse path describes the parse route through the message to an element to be extracted from the message. The extracted element may be 
any JSON type (null, boolean, number, string, array, object) and the string representation of the extracted element is placed into a column in the
sink table. A parse path corresponds to the column name in the db.columns property in the corresponding list position.

A parse path represents an element hierarchy and is expressed as a string element identifiers, separated by a delimiting character (typically /).

* A child element wihin an object is specified using @key where key is the key of the child element. 
* A child element within an array is specified using #index where index is the index of the child element (starting at 0).

A path must start with the delimiter used to separate element identifiers. This first character is arbitrary and can be chosen to avoid
conflict with key names. 

Below are some examples for paths in the following message:

  {"identity":71293145,"location":{"latitude":51.5009449,"longitude":-2.4773414},"acceleration":[0.01,0.0,0.0]}

* /@identity - the path to element 71293145.
* /@location/@longitude - the path to element -2.4773414.
* /@acceleration/#0 - the path to element 0.01
* /@location - the path to element {"latitude":51.5009449, "longitude":-2.4773414}

The data type of a column receiving an element must be compatible with the element value passed to it. 
When a non scalar element (object or aray) is passed into a column, the target column should be a TEXT, JSON or VARCHAR data type. 

To insert messages in the above format into a table with an id, latitude, longitude, and acceleration columns, the db.columns and db.json.parse configuration properties would be:

* db.columns = id,latitude,longitude,acceleration
* db.json.parse = /@identity,/@location/@latitude,/@location/@longitude,/@acceleration

Note the corresponding positions between columns and their respective parse paths.

Where a path does not exist in the JSON message, a null value is placed in the column value. For example /@foo/@bar would return a null
value from the example message above.

## Internals

For synchronized delivery, a package of pl/pgsql functions is installed in the database.

The package provides the functions for starting, flushing and getting state to ensure synchronized delivery semantics (exactly once) 
from Kafka to the sink table.

The functions and state information are stored in the "$justone$kafka$connect$sink" schema. Within this schema, each sink table has a 
corresponding state table called schema.table using the schema and name of the sink table. 

A state table contains a row for each topic, partition and offset and has the following table definition

         Column      |       Type        | Modifiers
    -----------------+-------------------+-----------
     kafka_topic     | character varying | not null
     kafka_partition | integer           | not null
     kafka_offset    | bigint            | not null

The start() function is called when a Kafka sink task is started. It creates a temporary sink table and also creates a Kafka 
state table if it does not already exist. The sink task can then insert rows into the temporary sink table.

The state() function returns rows from the state table for a specified sink table. This information can be used by the sink 
connector to initialise offsets for synchronizing consumption with the table state. Note that this function may return no
rows if the sink table has not been flushed. 

The flush() function is called during a sink task flush. It copies rows from the temporary sink table to the permanent sink table
and refreshes the Kafka state information in the state table. This is performed in the same transaction to guarantee 
synchronization.

The drop() function is called to drop synchronization state if non synchronized delivery is used by the sink task.

Process flow is typically:

    SELECT "$justone$kafka$connect$sink".start(<schema>,<table>);
    SELECT kafkaTopic,kafkaPartition,kafkaOffset FROM "$justone$kafka$connect$sink".state(<schema>,<table>);
    ...insert rows into temporay sink table...
    SELECT "$justone$kafka$connect$sink".flush(<schema>,<table>,<topics>,<partitions>,<offsets>);

## Dependencies

* Kafka Connect API - connect-api-0.9.0.1.jar
* JustOne json parser - justone-json-1.0.jar
* JustOne pg writer - justone-pgwriter-1.0.jar

## Support

Email support@justonedb.com for support.

## Author

Duncan Pauly
