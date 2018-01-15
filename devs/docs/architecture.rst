=====================
CrateDB Architecture
=====================

CrateDB is a distributed SQL database. From a high-level perspective, the
following components can be found in each node of CrateDB:

.. image:: ../resources/architecture.svg

Components
-------------

SQL Engine
............

CrateDB's heart consists of a SQL Engine which takes care of parsing SQL
statements and executing them in the cluster.

The engine is comprised of the following components:

1. Parser: Breaks down the SQL statement into its components.
2. Analyzer: Performs semantic processing of the statement including
   verification, type annotation, and optimization.
3. Planner: Builds an execution plan from the analyzed statement.
4. Executor: Executes the plan in the cluster and collects results.

Class entry point:

- `SqlParser`
- `RelationAnalyzer`
- `Planner`
- `LogicalPlan` / `Plan`
- `NodeOperationTree`
- `ExecutionPhasesTask`
- `BatchIterator`

Input
.....

When you first use CrateDB, you probably want to check out its Web
Interface. The Web Interface provides an good overview of your cluster nodes,
its health, and a list of your tables. You can also execute queries from it.

CrateDB also has a range of different connectors. For a detailed list, please
see:
`https://crate.io/docs/crate/getting-started/en/latest/start-building/index.html`

The Web Interface as well as the connectors all make use of either the REST
interface or the Postgres Wire Protocol. The REST interface is HTTP-based
whereas the Postgres protocol uses the protocol specification from the Postgres
project. Thus, the Postgres protocol enables you to use CrateDB for applications
which were originally built to communicate with Postgres. You may also use
Postgres tools like `psql` with CrateDB.

Class entry points:

- `PostgresWireProtocol`
- `CrateRestMainAction`

Transport
..........

Transport denotes the communication between nodes in a CrateDB cluster. CrateDB
uses Netty and Elasticsearch to transfer data between nodes.

Replication ensures that data is available on multiple nodes and hardware
failures can be tolerated.

Class entry points:

- `TransportRequest`
- `TransportJobAction`

Storage
........

CrateDB enables you to store your data in tables like you would in a traditional
SQL database with a strict schema. Additionally, you can dynamically adjust the
schema or store JSON objects inside columns which you can also query. Data is
clustered by a column or expression to distribute the data. Partitioned tables
allow you to further distribute and split up your data using other columns or
expressions.

To be able to retrieve data efficiently and perform aggregations, CrateDB uses
Lucene for indexing and storing data. Lucene itself stores a document with the
contents of each row. Retrieval is really efficient because the fields of the
document are indexed. Aggregations can also be performed efficiently due to
Lucene's column store feature which stores columns separately to quickly perform
aggregations with them.

Class entry points:

- `LuceneQueryBuilder`
- `LuceneBatchIterator`

Enterprise
..........

The enterprise version of CrateDB contains features which are additions to the
main database functionality.
